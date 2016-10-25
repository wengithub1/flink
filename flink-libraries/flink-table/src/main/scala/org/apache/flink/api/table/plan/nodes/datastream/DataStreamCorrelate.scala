/*
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
package org.apache.flink.api.table.plan.nodes.datastream
import org.apache.calcite.plan.{RelOptCluster, RelOptCost, RelOptPlanner, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.logical.LogicalTableFunctionScan
import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.calcite.rex.{RexCall, RexNode}
import org.apache.calcite.sql.SemiJoinType
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.table.StreamTableEnvironment
import org.apache.flink.api.table.codegen.CodeGenerator
import org.apache.flink.api.table.functions.utils.TableValuedSqlFunction
import org.apache.flink.api.table.plan.nodes.FlinkCorrelate
import org.apache.flink.api.table.typeutils.TypeConverter._
import org.apache.flink.streaming.api.datastream.DataStream

/**
  * Flink RelNode which matches along with cross join a user defined table valued function.
  */
class DataStreamCorrelate(
                           cluster: RelOptCluster,
                           traitSet: RelTraitSet,
                           input: RelNode,
                           scan: LogicalTableFunctionScan,
                           condition: RexNode,
                           rowType: RelDataType,
                           joinRowType: RelDataType,
                           joinType: SemiJoinType,
                           ruleDescription: String)
  extends SingleRel(cluster, traitSet, input)
    with FlinkCorrelate
    with DataStreamRel {
  override def deriveRowType() = rowType


  override def computeSelfCost(planner: RelOptPlanner, metadata: RelMetadataQuery): RelOptCost = {
    val rowCnt = metadata.getRowCount(getInput) + 10
    planner.getCostFactory.makeCost(rowCnt, rowCnt, 0)
  }

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new DataStreamCorrelate(
      cluster,
      traitSet,
      inputs.get(0),
      scan,
      condition,
      rowType,
      joinRowType,
      joinType,
      ruleDescription)
  }

  override def toString: String = {
    val funcRel = unwrap(scan)
    val rexCall = funcRel.getCall.asInstanceOf[RexCall]
    val sqlFunction = rexCall.getOperator.asInstanceOf[TableValuedSqlFunction]
    correlateToString(rexCall, sqlFunction)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    val funcRel = unwrap(scan)
    val rexCall = funcRel.getCall.asInstanceOf[RexCall]
    val sqlFunction = rexCall.getOperator.asInstanceOf[TableValuedSqlFunction]
    super.explainTerms(pw)
      .item("lateral", correlateToString(rexCall, sqlFunction))
      .item("select", selectToString(rowType))
  }


  override def translateToPlan(tableEnv: StreamTableEnvironment,
                               expectedType: Option[TypeInformation[Any]]): DataStream[Any] = {

    val config = tableEnv.getConfig
    val returnType = determineReturnType(
      getRowType,
      expectedType,
      config.getNullCheck,
      config.getEfficientTypeUsage)

    val inputDS = input.asInstanceOf[DataStreamRel]
      .translateToPlan(tableEnv, Some(inputRowType(input)))

    val funcRel = scan.asInstanceOf[LogicalTableFunctionScan]
    val rexCall = funcRel.getCall.asInstanceOf[RexCall]
    val sqlFunction = rexCall.getOperator.asInstanceOf[TableValuedSqlFunction]
    val udtfTypeInfo = sqlFunction.getRowTypeInfo.asInstanceOf[TypeInformation[Any]]

    val generator = new CodeGenerator(
      config,
      false,
      inputDS.getType,
      Some(udtfTypeInfo))

    val body = functionBody(
      generator,
      udtfTypeInfo,
      getRowType,
      rexCall,
      condition,
      config,
      joinType,
      expectedType)

    val genFunction = generator.generateFunction(
      ruleDescription,
      classOf[FlatMapFunction[Any, Any]],
      body,
      returnType)

    val mapFunc = correlateMapFunction(genFunction)

    inputDS.flatMap(mapFunc).name(correlateOpName(rexCall, sqlFunction, rowType))
  }

}
