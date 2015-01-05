/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.master;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tajo.QueryId;
import org.apache.tajo.TaskAttemptId;
import org.apache.tajo.TaskId;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.Column;
import org.apache.tajo.catalog.Schema;
import org.apache.tajo.catalog.TableDesc;
import org.apache.tajo.catalog.TableMeta;
import org.apache.tajo.catalog.proto.CatalogProtos.ColumnProto;
import org.apache.tajo.catalog.proto.CatalogProtos.DatabaseProto;
import org.apache.tajo.catalog.proto.CatalogProtos.IndexProto;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.catalog.proto.CatalogProtos.TableDescriptorProto;
import org.apache.tajo.catalog.proto.CatalogProtos.TableOptionProto;
import org.apache.tajo.catalog.proto.CatalogProtos.TablePartitionProto;
import org.apache.tajo.catalog.proto.CatalogProtos.TableStatsProto;
import org.apache.tajo.catalog.proto.CatalogProtos.TablespaceProto;
import org.apache.tajo.catalog.statistics.TableStats;
import org.apache.tajo.common.TajoDataTypes.DataType;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.datum.DatumFactory;
import org.apache.tajo.engine.codegen.CompilationError;
import org.apache.tajo.engine.planner.PhysicalPlannerImpl;
import org.apache.tajo.engine.planner.Projector;
import org.apache.tajo.engine.planner.global.ExecutionBlock;
import org.apache.tajo.engine.planner.global.ExecutionBlockCursor;
import org.apache.tajo.engine.planner.global.GlobalPlanner;
import org.apache.tajo.engine.planner.global.MasterPlan;
import org.apache.tajo.engine.planner.physical.PhysicalExec;
import org.apache.tajo.engine.query.QueryContext;
import org.apache.tajo.master.TajoMaster.MasterContext;
import org.apache.tajo.plan.LogicalPlan;
import org.apache.tajo.plan.PlanningException;
import org.apache.tajo.plan.expr.EvalNode;
import org.apache.tajo.plan.logical.IndexScanNode;
import org.apache.tajo.plan.logical.LogicalNode;
import org.apache.tajo.plan.logical.ScanNode;
import org.apache.tajo.storage.RowStoreUtil;
import org.apache.tajo.storage.Tuple;
import org.apache.tajo.storage.VTuple;
import org.apache.tajo.storage.RowStoreUtil.RowStoreEncoder;
import org.apache.tajo.util.KeyValueSet;
import org.apache.tajo.util.TUtil;
import org.apache.tajo.worker.TaskAttemptContext;

import com.google.protobuf.ByteString;

public class NonForwardQueryResultSystemScanner implements NonForwardQueryResultScanner {
  
  private final Log LOG = LogFactory.getLog(getClass());
  
  private MasterContext masterContext;
  private LogicalPlan logicalPlan;
  private final QueryId queryId;
  private final String sessionId;
  private TaskAttemptContext taskContext;
  private int currentRow;
  private long maxRow;
  private TableDesc tableDesc;
  private Schema outSchema;
  private RowStoreEncoder encoder;
  private PhysicalExec physicalExec;
  
  public NonForwardQueryResultSystemScanner(MasterContext context, LogicalPlan plan, QueryId queryId, 
      String sessionId, int maxRow) {
    masterContext = context;
    logicalPlan = plan;
    this.queryId = queryId;
    this.sessionId = sessionId;
    this.maxRow = maxRow;
    
  }
  
  @Override
  public void init() throws IOException {
    QueryContext queryContext = new QueryContext(masterContext.getConf());
    currentRow = 0;
    
    MasterPlan masterPlan = new MasterPlan(queryId, queryContext, logicalPlan);
    GlobalPlanner globalPlanner = new GlobalPlanner(masterContext.getConf(), masterContext.getCatalog());
    try {
      globalPlanner.build(masterPlan);
    } catch (PlanningException e) {
      throw new RuntimeException(e);
    }
    
    ExecutionBlockCursor cursor = new ExecutionBlockCursor(masterPlan);
    ExecutionBlock leafBlock = null;
    while (cursor.hasNext()) {
      ExecutionBlock block = cursor.nextBlock();
      if (masterPlan.isLeaf(block)) {
        leafBlock = block;
        break;
      }
    }
    
    taskContext = new TaskAttemptContext(queryContext, null,
        new TaskAttemptId(new TaskId(leafBlock.getId(), 0), 0),
        null, null);
    physicalExec = new SimplePhysicalPlannerImpl(masterContext.getConf())
      .createPlan(taskContext, leafBlock.getPlan());
    
    tableDesc = new TableDesc("table_"+System.currentTimeMillis(), physicalExec.getSchema(), 
        new TableMeta(StoreType.SYSTEM, new KeyValueSet()), null);
    outSchema = physicalExec.getSchema();
    encoder = RowStoreUtil.createEncoder(getLogicalSchema());
    
    physicalExec.init();
  }

  @Override
  public void close() throws Exception {
    tableDesc = null;
    outSchema = null;
    encoder = null;
    if (physicalExec != null) {
      try {
        physicalExec.close();
      } catch (Exception ignored) {}
    }
    physicalExec = null;
    currentRow = -1;
  }
  
  private List<Tuple> getTablespaces(Schema outSchema) {
    List<TablespaceProto> tablespaces = masterContext.getCatalog().getAllTablespaces();
    List<Tuple> tuples = new ArrayList<Tuple>(tablespaces.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (TablespaceProto tablespace: tablespaces) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        if ("space_id".equalsIgnoreCase(column.getSimpleName())) {
          if (tablespace.hasId()) {
            aTuple.put(fieldId, DatumFactory.createInt4(tablespace.getId()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        } else if ("space_name".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(tablespace.getSpaceName()));
        } else if ("space_handler".equalsIgnoreCase(column.getSimpleName())) {
          if (tablespace.hasHandler()) {
            aTuple.put(fieldId, DatumFactory.createText(tablespace.getHandler()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        } else if ("space_uri".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(tablespace.getUri()));
        }
      }
      tuples.add(aTuple);
    }
    
    return tuples;    
  }
  
  private List<Tuple> getDatabases(Schema outSchema) {
    List<DatabaseProto> databases = masterContext.getCatalog().getAllDatabases();
    List<Tuple> tuples = new ArrayList<Tuple>(databases.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (DatabaseProto database: databases) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        if ("db_id".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(database.getId()));
        } else if ("db_name".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(database.getName()));
        } else if ("space_id".equalsIgnoreCase(column.getSimpleName())) {
          if (database.hasSpaceId()) {
            aTuple.put(fieldId, DatumFactory.createInt4(database.getSpaceId()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getTables(Schema outSchema) {
    List<TableDescriptorProto> tables = masterContext.getCatalog().getAllTables();
    List<Tuple> tuples = new ArrayList<Tuple>(tables.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (TableDescriptorProto table: tables) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        if ("tid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(table.getTid()));
        } else if ("db_id".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(table.getDbId()));
        } else if ("table_name".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(table.getName()));
        } else if ("table_type".equalsIgnoreCase(column.getSimpleName())) {
          if (table.hasTableType()) {
            aTuple.put(fieldId, DatumFactory.createText(table.getTableType()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        } else if ("path".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(table.getPath()));
        } else if ("store_type".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(table.getStoreType()));
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getColumns(Schema outSchema) {
    List<ColumnProto> columnsList = masterContext.getCatalog().getAllColumns();
    List<Tuple> tuples = new ArrayList<Tuple>(columnsList.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    int columnId = 1, prevtid = -1, tid = 0;
    
    for (ColumnProto column: columnsList) {
      aTuple = new VTuple(outSchema.size());
      
      tid = column.getTid();
      if (prevtid != tid) {
        columnId = 1;
        prevtid = tid;
      }
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column colObj = columns.get(fieldId);
        
        if ("tid".equalsIgnoreCase(colObj.getSimpleName())) {
          if (column.hasTid()) {
            aTuple.put(fieldId, DatumFactory.createInt4(tid));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        } else if ("column_name".equalsIgnoreCase(colObj.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(column.getName()));
        } else if ("ordinal_position".equalsIgnoreCase(colObj.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(columnId));
        } else if ("data_type".equalsIgnoreCase(colObj.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(column.getDataType().getType().toString()));
        } else if ("type_length".equalsIgnoreCase(colObj.getSimpleName())) {
          DataType dataType = column.getDataType();
          if (dataType.hasLength()) {
            aTuple.put(fieldId, DatumFactory.createInt4(dataType.getLength()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        }
      }
      
      columnId++;
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getIndexes(Schema outSchema) {
    List<IndexProto> indexList = masterContext.getCatalog().getAllIndexes();
    List<Tuple> tuples = new ArrayList<Tuple>(indexList.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (IndexProto index: indexList) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        
        if ("db_id".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(index.getDbId()));
        } else if ("tid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(index.getTId()));
        } else if ("index_name".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(index.getIndexName()));
        } else if ("column_name".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(index.getColumnName()));
        } else if ("data_type".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(index.getDataType()));
        } else if ("index_type".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(index.getIndexType()));
        } else if ("is_unique".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createBool(index.getIsUnique()));
        } else if ("is_clustered".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createBool(index.getIsClustered()));
        } else if ("is_ascending".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createBool(index.getIsAscending()));
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getAllTableOptions(Schema outSchema) {
    List<TableOptionProto> optionList = masterContext.getCatalog().getAllTableOptions();
    List<Tuple> tuples = new ArrayList<Tuple>(optionList.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (TableOptionProto option: optionList) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        
        if ("tid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(option.getTid()));
        } else if ("key_".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(option.getKeyval().getKey()));
        } else if ("value_".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(option.getKeyval().getValue()));
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getAllTableStats(Schema outSchema) {
    List<TableStatsProto> statList = masterContext.getCatalog().getAllTableStats();
    List<Tuple> tuples = new ArrayList<Tuple>(statList.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (TableStatsProto stat: statList) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        
        if ("tid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(stat.getTid()));
        } else if ("num_rows".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt8(stat.getNumRows()));
        } else if ("num_bytes".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt8(stat.getNumBytes()));
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> getAllPartitions(Schema outSchema) {
    List<TablePartitionProto> partitionList = masterContext.getCatalog().getAllPartitions();
    List<Tuple> tuples = new ArrayList<Tuple>(partitionList.size());
    List<Column> columns = outSchema.getColumns();
    Tuple aTuple;
    
    for (TablePartitionProto partition: partitionList) {
      aTuple = new VTuple(outSchema.size());
      
      for (int fieldId = 0; fieldId < columns.size(); fieldId++) {
        Column column = columns.get(fieldId);
        
        if ("pid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(partition.getPid()));
        } else if ("tid".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(partition.getTid()));
        } else if ("partition_name".equalsIgnoreCase(column.getSimpleName())) {
          if (partition.hasPartitionName()) {
            aTuple.put(fieldId, DatumFactory.createText(partition.getPartitionName()));
          } else {
            aTuple.put(fieldId, DatumFactory.createNullDatum());
          }
        } else if ("ordinal_position".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createInt4(partition.getOrdinalPosition()));
        } else if ("path".equalsIgnoreCase(column.getSimpleName())) {
          aTuple.put(fieldId, DatumFactory.createText(partition.getPath()));
        }
      }
      
      tuples.add(aTuple);
    }
    
    return tuples;
  }
  
  private List<Tuple> fetchSystemTable(TableDesc tableDesc, Schema inSchema) {
    List<Tuple> tuples = null;
    String tableName = CatalogUtil.extractSimpleName(tableDesc.getName());

    if ("tablespace".equalsIgnoreCase(tableName)) {
      tuples = getTablespaces(inSchema);
    } else if ("databases".equalsIgnoreCase(tableName)) {
      tuples = getDatabases(inSchema);
    } else if ("tables".equalsIgnoreCase(tableName)) {
      tuples = getTables(inSchema);
    } else if ("columns".equalsIgnoreCase(tableName)) {
      tuples = getColumns(inSchema);
    } else if ("indexes".equalsIgnoreCase(tableName)) {
      tuples = getIndexes(inSchema);
    } else if ("table_options".equalsIgnoreCase(tableName)) {
      tuples = getAllTableOptions(inSchema);
    } else if ("table_stats".equalsIgnoreCase(tableName)) {
      tuples = getAllTableStats(inSchema);
    } else if ("partitions".equalsIgnoreCase(tableName)) {
      tuples = getAllPartitions(inSchema);
    }
    
    return tuples;    
  }

  @Override
  public List<ByteString> getNextRows(int fetchRowNum) throws IOException {
    List<ByteString> rows = new ArrayList<ByteString>();
    int startRow = currentRow;
    int endRow = startRow + fetchRowNum;
    
    if (physicalExec == null) {
      return rows;
    }
    
    while (currentRow < endRow) {
      Tuple currentTuple = physicalExec.next();
      
      if (currentTuple == null) {
        physicalExec.close();
        physicalExec = null;
        break;
      }
      
      currentRow++;
      rows.add(ByteString.copyFrom(encoder.toBytes(currentTuple)));
      
      if (currentRow >= maxRow) {
        physicalExec.close();
        physicalExec = null;
        break;
      }
    }
    
    return rows;
  }

  @Override
  public QueryId getQueryId() {
    return queryId;
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }
  
  @Override
  public TableDesc getTableDesc() {
    return tableDesc;
  }
  
  @Override
  public Schema getLogicalSchema() {
    return outSchema;
  }
  
  class SimplePhysicalPlannerImpl extends PhysicalPlannerImpl {

    public SimplePhysicalPlannerImpl(TajoConf conf) {
      super(conf);
    }

    @Override
    public PhysicalExec createScanPlan(TaskAttemptContext ctx, ScanNode scanNode, Stack<LogicalNode> node)
        throws IOException {
      return new SystemPhysicalExec(ctx, scanNode);
    }

    @Override
    public PhysicalExec createIndexScanExec(TaskAttemptContext ctx, IndexScanNode annotation) throws IOException {
      return new SystemPhysicalExec(ctx, annotation);
    }
  }
  
  class SystemPhysicalExec extends PhysicalExec {
    
    private ScanNode scanNode;
    private EvalNode qual;
    private Projector projector;
    private TableStats tableStats;
    private final List<Tuple> cachedData;
    private int currentRow;
    private boolean isClosed;

    public SystemPhysicalExec(TaskAttemptContext context, ScanNode scanNode) {
      super(context, scanNode.getInSchema(), scanNode.getOutSchema());
      this.scanNode = scanNode;
      this.qual = this.scanNode.getQual();
      cachedData = TUtil.newList();
      currentRow = 0;
      isClosed = false;
      
      projector = new Projector(context, inSchema, outSchema, scanNode.getTargets());
    }

    @Override
    public Tuple next() throws IOException {
      Tuple aTuple = null;
      Tuple outTuple = new VTuple(outColumnNum);
      
      if (isClosed) {
        return null;
      }
      
      if (cachedData.size() == 0) {
        rescan();
      }
      
      if (!scanNode.hasQual()) {
        if (currentRow < cachedData.size()) {
          aTuple = cachedData.get(currentRow++);
          projector.eval(aTuple, outTuple);
          outTuple.setOffset(aTuple.getOffset());
          return outTuple;
        }
        return null;
      } else {
        while (currentRow < cachedData.size()) {
          aTuple = cachedData.get(currentRow++);
          if (qual.eval(inSchema, aTuple).isTrue()) {
            projector.eval(aTuple, outTuple);
            return outTuple;
          }
        }
        return null;
      }
    }

    @Override
    public void rescan() throws IOException {
      cachedData.clear();
      cachedData.addAll(fetchSystemTable(scanNode.getTableDesc(), inSchema));

      tableStats = new TableStats();
      tableStats.setNumRows(cachedData.size());
    }

    @Override
    public void close() throws IOException {
      scanNode = null;
      qual = null;
      projector = null;
      cachedData.clear();
      currentRow = -1;
      isClosed = true;
    }

    @Override
    public float getProgress() {
      return 1.0f;
    }

    @Override
    protected void compile() throws CompilationError {
      if (scanNode.hasQual()) {
        qual = context.getPrecompiledEval(inSchema, qual);
      }
    }

    @Override
    public TableStats getInputStats() {
      return tableStats;
    }
    
  }

}