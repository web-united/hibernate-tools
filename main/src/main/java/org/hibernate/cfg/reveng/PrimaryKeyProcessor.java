package org.hibernate.cfg.reveng;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.hibernate.JDBCException;
import org.hibernate.cfg.JDBCBinderException;
import org.hibernate.cfg.reveng.dialect.MetaDataDialect;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PrimaryKey;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;
import org.hibernate.sql.Alias;
import org.jboss.logging.Logger;

public class PrimaryKeyProcessor {

	private static final Logger log = Logger.getLogger(PrimaryKeyProcessor.class);

	public static void processPrimaryKey(
			MetaDataDialect metaDataDialect,
			ReverseEngineeringStrategy revengStrategy,
			String defaultSchema,
			String defaultCatalog,
			DatabaseCollector dbs,
			Table table) {

		List<Object[]> columns = new ArrayList<Object[]>();
		PrimaryKey key = null;
		Iterator<Map<String, Object>> primaryKeyIterator = null;
		List<String> t;
		Iterator<?> cols;

		List<String> userPrimaryKey = RevEngUtils.getPrimaryKeyInfoInRevengStrategy(revengStrategy, table, defaultCatalog, defaultSchema);
		if(userPrimaryKey!=null && !userPrimaryKey.isEmpty()) {
			key = new PrimaryKey(table);
			key.setName(new Alias(15, "PK").toAliasString( table.getName()));
			key.setTable(table);
			if(table.getPrimaryKey()!=null) {
				throw new JDBCBinderException(table + " already has a primary key!"); //TODO: ignore ?
			}
			table.setPrimaryKey(key);
			t = new ArrayList<String>(userPrimaryKey);

			// Create a temporary unique key for the original primary key which is going to become a unique constraint
			// due to the primary key override
			Iterator<Map<String, Object>> primaryKeys = metaDataDialect.getPrimaryKeys(getCatalogForDBLookup(
					table.getCatalog(), defaultCatalog),
					getSchemaForDBLookup(table.getSchema(), defaultSchema),
					table.getName());
			Map<String, UniqueKey> uniqueKeys = new HashMap<String, UniqueKey>();

			while (primaryKeys.hasNext() ) {
				Map<String, Object> primaryKeyRs = primaryKeys.next();
				String indexName = (String) primaryKeyRs.get("PK_NAME");
				String columnName = (String) primaryKeyRs.get("COLUMN_NAME");

				UniqueKey uniqueKey = uniqueKeys.get(indexName);
				if (uniqueKey==null) {
					uniqueKey = new UniqueKey();
					uniqueKey.setName("DWPK-" + indexName);
					uniqueKey.setTable(table);
					table.addUniqueKey(uniqueKey);
					uniqueKeys.put(indexName, uniqueKey);

					// Add a temporary column so that this unique constraint is different from the original primary key.
					// This unique key with the temporary column will be deleted during index processing.
					Column tempColumn = new Column("TEMP_COLUMN");
					uniqueKey.addColumn(tempColumn);
				}

				Column column = getColumn(metaDataDialect, table, columnName);
				uniqueKey.addColumn(column);
			}
		} else {
			log.warn("Rev.eng. strategy did not report any primary key columns for " + table.getName() + ". Asking the JDBC driver");
			try {
				Map<String, Object> primaryKeyRs = null;
				primaryKeyIterator = metaDataDialect.getPrimaryKeys(getCatalogForDBLookup(table.getCatalog(), defaultCatalog), getSchemaForDBLookup(table.getSchema(), defaultSchema), table.getName() );

				while (primaryKeyIterator.hasNext() ) {
					primaryKeyRs = primaryKeyIterator.next();

				/*String ownCatalog = primaryKeyRs.getString("TABLE_CAT");
				 String ownSchema = primaryKeyRs.getString("TABLE_SCHEM");
				 String ownTable = primaryKeyRs.getString("TABLE_NAME");*/

					String columnName = (String) primaryKeyRs.get("COLUMN_NAME");
					short seq = ((Short)primaryKeyRs.get("KEY_SEQ")).shortValue();
					String name = (String) primaryKeyRs.get("PK_NAME");

					if(key==null) {
						key = new PrimaryKey(table);
						key.setName(name);
						key.setTable(table);
						if(table.getPrimaryKey()!=null) {
							throw new JDBCBinderException(table + " already has a primary key!"); //TODO: ignore ?
						}
						table.setPrimaryKey(key);
					}
					else {
						if(!(name==key.getName() ) && name!=null && !name.equals(key.getName() ) ) {
							throw new JDBCBinderException("Duplicate names found for primarykey. Existing name: " + key.getName() + " JDBC name: " + name + " on table " + table);
						}
					}

					columns.add(new Object[] { new Short(seq), columnName});
				}
			} finally {
				if (primaryKeyIterator!=null) {
					try {
						metaDataDialect.close(primaryKeyIterator);
					} catch(JDBCException se) {
						log.warn("Exception when closing resultset for reading primary key information",se);
					}
				}
			}

			// sort the columns accoring to the key_seq.
			Collections.sort(columns,new Comparator<Object[]>() {
				public int compare(Object[] o1, Object[] o2) {
					Short left = (Short)o1[0];
					Short right = (Short)o2[0];
					return left.compareTo(right);
				}
			});

			t = new ArrayList<String>(columns.size());
			cols = columns.iterator();
			while (cols.hasNext() ) {
				Object[] element = (Object[]) cols.next();
				t.add((String)element[1]);
			}
		}

		Iterator<Map<String, Object>> suggestedPrimaryKeyStrategyName = metaDataDialect.getSuggestedPrimaryKeyStrategyName( getCatalogForDBLookup(table.getCatalog(), defaultCatalog), getSchemaForDBLookup(table.getSchema(), defaultSchema), table.getName() );
		try {
			if(suggestedPrimaryKeyStrategyName.hasNext()) {
				Map<String, Object> m = suggestedPrimaryKeyStrategyName.next();
				String suggestion = (String) m.get( "HIBERNATE_STRATEGY" );
				if(suggestion!=null) {
					dbs.addSuggestedIdentifierStrategy(
							transformForModelLookup(table.getCatalog(), defaultCatalog),
							transformForModelLookup(table.getSchema(), defaultSchema),
							table.getName(),
							suggestion );
				}
			}
		} finally {
			if(suggestedPrimaryKeyStrategyName!=null) {
				try {
					metaDataDialect.close(suggestedPrimaryKeyStrategyName);
				} catch(JDBCException se) {
					log.warn("Exception while closing iterator for suggested primary key strategy name",se);
				}
			}
		}

		if(key!=null) {
			cols = t.iterator();
			while (cols.hasNext() ) {
				String name = (String) cols.next();
				// should get column from table if it already exists!
				Column col = getColumn(metaDataDialect, table, name);
				// if the sql type code is null (e.g. for excluded columns), populate it.
				if (col.getSqlTypeCode() == null) {
					updateColumnInfo(metaDataDialect, defaultSchema, defaultCatalog, col, table, name);
				}
				key.addColumn(col);
			}
			log.debug("primary key for " + table + " -> "  + key);
		}

	}

	private static void updateColumnInfo(MetaDataDialect metaDataDialect, String defaultSchema, String defaultCatalog,
										 Column col, Table table, String name) {
		Iterator<Map<String, Object>> columnIterator = metaDataDialect.getColumns(
				getCatalogForDBLookup(table.getCatalog(), defaultCatalog),
				getSchemaForDBLookup(table.getSchema(), defaultSchema),
				table.getName(),
				name);

		if (columnIterator.hasNext()) {
			Map<String, Object> columnRs = columnIterator.next();

			int sqlTypeCode = (Integer) columnRs.get("DATA_TYPE");
			int size = (Integer) columnRs.get("COLUMN_SIZE");
			int dbNullability = (Integer) columnRs.get("NULLABLE");

			boolean isNullable = dbNullability != DatabaseMetaData.columnNoNulls;

			col.setSqlTypeCode(sqlTypeCode);
			if (size>=0 && size!=Integer.MAX_VALUE && JDBCToHibernateTypeHelper.typeHasLength(sqlTypeCode)) {
				col.setLength(size);
			}
			col.setNullable(isNullable);
		}
	}

	private static String getCatalogForDBLookup(String catalog, String defaultCatalog) {
		return catalog==null?defaultCatalog:catalog;			
	}

	private static String transformForModelLookup(String id, String defaultId) {
		return id == null || id.equals(defaultId) ? null : id;			
	}

	private static String getSchemaForDBLookup(String schema, String defaultSchema) {
		return schema==null?defaultSchema:schema;
	}

	private static Column getColumn(MetaDataDialect metaDataDialect, Table table, String columnName) {
		Column column = new Column();
		column.setName(quote(metaDataDialect, columnName));
		Column existing = table.getColumn(column);
		if(existing!=null) {
			column = existing;
		}
		return column;
	}

	private static String quote(MetaDataDialect metaDataDialect, String columnName) {
		   if(columnName==null) return columnName;
		   if(metaDataDialect.needQuote(columnName)) {
			   if(columnName.length()>1 && columnName.charAt(0)=='`' && columnName.charAt(columnName.length()-1)=='`') {
				   return columnName; // avoid double quoting
			   }
			   return "`" + columnName + "`";
		   } else {
			   return columnName;
		   }		
	}

}
