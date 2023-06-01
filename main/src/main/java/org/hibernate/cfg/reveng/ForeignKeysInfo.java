package org.hibernate.cfg.reveng;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Table;

public class ForeignKeysInfo {

	final Map<String, Table> dependentTables;
	final Map<String, List<Column>> dependentColumns;
	final Map<String, List<Column>> referencedColumns;
	private final Table referencedTable;
	
	public ForeignKeysInfo(
			Table referencedTable, 
			Map<String, Table> tables, 
			Map<String, List<Column>> columns, 
			Map<String, List<Column>> refColumns) {
		this.referencedTable = referencedTable;
		this.dependentTables = tables;
		this.dependentColumns = columns;
		this.referencedColumns = refColumns;
	}
	
	Map<String, List<ForeignKey>> process(ReverseEngineeringStrategy revengStrategy) {
		Map<String, List<ForeignKey>> oneToManyCandidates = new HashMap<String, List<ForeignKey>>();
        Iterator<Entry<String, Table>> iterator = dependentTables.entrySet().iterator();
		while (iterator.hasNext() ) {
			Entry<String, Table> entry = iterator.next();
			String fkName = entry.getKey();
			Table fkTable = entry.getValue();			
			List<Column> columns = dependentColumns.get(fkName);
			List<Column> refColumns = referencedColumns.get(fkName);
			
			String className = revengStrategy.tableToClassName(TableIdentifier.create(referencedTable) );

			// ForeignKeyKey matches only by columns and referenced columns without considering the referenced class
			// name. Added a boolean to check if there exists a foreign key where the columns and referenced columns
			// match, but the referenced class name is different. In this case add a temporary column to the actual
			// foreign key so that hibernate sees this as a different key, then remove it once hibernate returns
			// the newly created key.
			boolean differentTargetFound = fkTable.getForeignKeys().keySet().stream().anyMatch(fk -> {
				String keyDef = fk.toString();
				String colsDef = keyDef.substring(keyDef.indexOf("ForeignKeyKey{columns=[")+23,
						keyDef.indexOf("], referencedClassName='"));
				List<Column> cols = createColumnList(colsDef);
				String classDef = keyDef.substring(keyDef.indexOf(", referencedClassName='")+23,
						keyDef.indexOf("', referencedColumns="));
				String refColsDef = keyDef.substring(keyDef.indexOf("', referencedColumns=[")+22,
						keyDef.length()-2);
				List<Column> refCols = createColumnList(refColsDef);

				return columns.equals(cols) && refColumns.equals(refCols) && !className.equals(classDef);
			});

			Column tempColumn = new Column("TEMP_COLUMN");
			if (differentTargetFound) {
				refColumns.add(tempColumn);
			}

			ForeignKey key = fkTable.createForeignKey(fkName, columns, className, null, refColumns);
			key.setReferencedTable(referencedTable);

			if (differentTargetFound) {
				key.getReferencedColumns().remove(tempColumn);
			}

			addToMultiMap(oneToManyCandidates, className, key);				
		}
		return oneToManyCandidates;
	}

	private List<Column> createColumnList(String columnDef) {
		String[] colsStr = columnDef.split("\\s*,\\s*");
		List<Column> cols = new ArrayList<>();
		for (String col: colsStr) {
			String colName = col.substring(col.indexOf("org.hibernate.mapping.Column(")+29, col.length()-1);
			cols.add(new Column(colName));
		}
		return cols;
	}

	private void addToMultiMap(Map<String, List<ForeignKey>> multimap, String key, ForeignKey item) {
		List<ForeignKey> existing = multimap.get(key);
		if(existing == null) {
			existing = new ArrayList<ForeignKey>();
			multimap.put(key, existing);
		}
		existing.add(item);
	}

}
