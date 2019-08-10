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

package org.apache.flink.table.planner.plan.nodes.physical.stream

import java.util

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.{RelNode, RelWriter, SingleRel}
import org.apache.calcite.tools.RelBuilder
import org.apache.flink.api.dag.Transformation
import org.apache.flink.streaming.api.transformations.OneInputTransformation
import org.apache.flink.table.api.{TableConfig, TableException}
import org.apache.flink.table.dataformat.BaseRow
import org.apache.flink.table.planner.calcite.FlinkRelBuilder.PlannerNamedWindowProperty
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.codegen.agg.AggsHandlerCodeGenerator
import org.apache.flink.table.planner.codegen.{CodeGeneratorContext, EqualiserCodeGenerator}
import org.apache.flink.table.planner.delegation.StreamPlanner
import org.apache.flink.table.planner.plan.logical._
import org.apache.flink.table.planner.plan.nodes.exec.{ExecNode, StreamExecNode}
import org.apache.flink.table.planner.plan.rules.physical.stream.StreamExecRetractionRules
import org.apache.flink.table.planner.plan.utils.AggregateUtil.{hasRowIntervalType, hasTimeIntervalType, isProctimeAttribute, isRowtimeAttribute, toDuration, toLong, transformToStreamAggregateInfoList}
import org.apache.flink.table.planner.plan.utils.{AggregateInfoList, KeySelectorUtil, RelExplainUtil, WindowEmitStrategy}
import org.apache.flink.table.runtime.generated.GeneratedRecordEqualiser
import org.apache.flink.table.runtime.operators.window.{CountWindow, TimeWindow, WindowOperator, WindowOperatorBuilder}
import org.apache.flink.table.runtime.types.LogicalTypeDataTypeConverter.fromDataTypeToLogicalType
import org.apache.flink.table.runtime.typeutils.BaseRowTypeInfo
import org.apache.flink.table.types.logical.LogicalType

import scala.collection.JavaConversions._

/**
  * Base streaming group window aggregate physical node for either aggregate or table aggregate.
  */
abstract class StreamExecGroupWindowAggregateBase(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    outputRowType: RelDataType,
    inputRowType: RelDataType,
    grouping: Array[Int],
    val aggCalls: Seq[AggregateCall],
    val window: LogicalWindow,
    namedProperties: Seq[PlannerNamedWindowProperty],
    inputTimeFieldIndex: Int,
    val emitStrategy: Option[WindowEmitStrategy],
    aggType: String)
  extends SingleRel(cluster, traitSet, inputRel)
    with StreamPhysicalRel
    with StreamExecNode[BaseRow] {

  override def producesUpdates: Boolean = emitStrategy.isDefined && emitStrategy.get.produceUpdates

  override def consumesRetractions = true

  override def needsUpdatesAsRetraction(input: RelNode) = true

  override def producesRetractions: Boolean = false

  override def requireWatermark: Boolean = window match {
    case TumblingGroupWindow(_, timeField, size)
      if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) => true
    case SlidingGroupWindow(_, timeField, size, _)
      if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) => true
    case SessionGroupWindow(_, timeField, _)
      if isRowtimeAttribute(timeField) => true
    case _ => false
  }

  def getGrouping: Array[Int] = grouping

  def getWindowProperties: Seq[PlannerNamedWindowProperty] = namedProperties

  override def deriveRowType(): RelDataType = outputRowType

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw)
      .itemIf("groupBy", RelExplainUtil.fieldToString(grouping, inputRowType), grouping.nonEmpty)
      .item("window", window)
      .itemIf("properties", namedProperties.map(_.name).mkString(", "), namedProperties.nonEmpty)
      .item("select", RelExplainUtil.streamWindowAggregationToString(
        inputRowType,
        grouping,
        outputRowType,
        aggCalls,
        namedProperties))
      .itemIf("emit",
        emitStrategy.getOrElse(""), emitStrategy.isDefined && !emitStrategy.get.toString.isEmpty)
  }

  //~ ExecNode methods -----------------------------------------------------------

  override def getInputNodes: util.List[ExecNode[StreamPlanner, _]] = {
    getInputs.map(_.asInstanceOf[ExecNode[StreamPlanner, _]])
  }

  override def replaceInputNode(
    ordinalInParent: Int,
    newInputNode: ExecNode[StreamPlanner, _]): Unit = {
    replaceInput(ordinalInParent, newInputNode.asInstanceOf[RelNode])
  }

  override protected def translateToPlanInternal(
    planner: StreamPlanner): Transformation[BaseRow] = {
    val config = planner.getTableConfig

    val inputTransform = getInputNodes.get(0).translateToPlan(planner)
      .asInstanceOf[Transformation[BaseRow]]

    val inputRowTypeInfo = inputTransform.getOutputType.asInstanceOf[BaseRowTypeInfo]
    val outRowType = BaseRowTypeInfo.of(FlinkTypeFactory.toLogicalRowType(outputRowType))

    val inputIsAccRetract = StreamExecRetractionRules.isAccRetract(input)

    if (inputIsAccRetract) {
      throw new TableException(
        s"Group Window $aggType: Retraction on windowed GroupBy $aggType is not supported yet. \n" +
          "please re-check sql grammar. \n" +
          s"Note: Windowed GroupBy $aggType should not follow a" +
          "non-windowed GroupBy aggregation.")
    }

    val isCountWindow = window match {
      case TumblingGroupWindow(_, _, size) if hasRowIntervalType(size) => true
      case SlidingGroupWindow(_, _, size, _) if hasRowIntervalType(size) => true
      case _ => false
    }

    if (isCountWindow && grouping.length > 0 && config.getMinIdleStateRetentionTime < 0) {
      LOG.warn(
        "No state retention interval configured for a query which accumulates state. " +
          "Please provide a query configuration with valid retention interval to prevent " +
          "excessive state size. You may specify a retention time of 0 to not clean up the state.")
    }

    val aggString = RelExplainUtil.streamWindowAggregationToString(
      inputRowType,
      grouping,
      outputRowType,
      aggCalls,
      namedProperties)

    val timeIdx = if (isRowtimeAttribute(window.timeAttribute)) {
      if (inputTimeFieldIndex < 0) {
        throw new TableException(
          s"Group window $aggType must defined on a time attribute, " +
            "but the time attribute can't be found.\n" +
            "This should never happen. Please file an issue."
        )
      }
      inputTimeFieldIndex
    } else {
      -1
    }

    val needRetraction = StreamExecRetractionRules.isAccRetract(getInput)
    val aggInfoList = transformToStreamAggregateInfoList(
      aggCalls,
      inputRowType,
      Array.fill(aggCalls.size)(needRetraction),
      needInputCount = needRetraction,
      isStateBackendDataViews = true)

    val aggCodeGenerator = getAggsHandlerCodeGenerator(
      aggInfoList,
      config,
      planner.getRelBuilder,
      inputRowTypeInfo.getLogicalTypes,
      needRetraction)

    val aggResultTypes = aggInfoList.getActualValueTypes.map(fromDataTypeToLogicalType)
    val windowPropertyTypes = namedProperties.map(_.property.resultType).toArray
    val generator = new EqualiserCodeGenerator(aggResultTypes ++ windowPropertyTypes)
    val equaliser = generator.generateRecordEqualiser("WindowValueEqualiser")

    val aggValueTypes = aggInfoList.getActualValueTypes.map(fromDataTypeToLogicalType)
    val accTypes = aggInfoList.getAccTypes.map(fromDataTypeToLogicalType)
    val operator = createWindowOperator(
      config,
      aggCodeGenerator,
      equaliser,
      accTypes,
      windowPropertyTypes,
      aggValueTypes,
      inputRowTypeInfo.getLogicalTypes,
      timeIdx,
      aggInfoList)

    val transformation = new OneInputTransformation(
      inputTransform,
      getRelDetailedDescription,
      operator,
      outRowType,
      inputTransform.getParallelism)

    if (inputsContainSingleton()) {
      transformation.setParallelism(1)
      transformation.setMaxParallelism(1)
    }

    val selector = KeySelectorUtil.getBaseRowSelector(grouping, inputRowTypeInfo)

    // set KeyType and Selector for state
    transformation.setStateKeySelector(selector)
    transformation.setStateKeyType(selector.getProducedType)
    transformation
  }

  private def getAggsHandlerCodeGenerator(
    aggInfoList: AggregateInfoList,
    config: TableConfig,
    relBuilder: RelBuilder,
    fieldTypeInfos: Seq[LogicalType],
    needRetraction: Boolean): AggsHandlerCodeGenerator = {

    val needMerge = window match {
      case SlidingGroupWindow(_, _, size, _) if hasTimeIntervalType(size) => true
      case SessionGroupWindow(_, _, _) => true
      case _ => false
    }

    val generator = new AggsHandlerCodeGenerator(
      CodeGeneratorContext(config),
      relBuilder,
      fieldTypeInfos,
      copyInputField = false)

    generator.needAccumulate()
    if (needMerge) {
      generator.needMerge(mergedAccOffset = 0, mergedAccOnHeap = false)
    }
    if (needRetraction) {
      generator.needRetract()
    }

    generator
  }

  protected def enrichWindowOperatorBuilder(
    inputWindowOperatorBuilder: WindowOperatorBuilder,
    inputFields: Seq[LogicalType],
    timeIdx: Int): WindowOperatorBuilder = {

    val builder = inputWindowOperatorBuilder
      .withInputFields(inputFields.toArray)

    val newBuilder = window match {
      case TumblingGroupWindow(_, timeField, size)
        if isProctimeAttribute(timeField) && hasTimeIntervalType(size) =>
        builder.tumble(toDuration(size)).withProcessingTime()

      case TumblingGroupWindow(_, timeField, size)
        if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) =>
        builder.tumble(toDuration(size)).withEventTime(timeIdx)

      case TumblingGroupWindow(_, timeField, size)
        if isProctimeAttribute(timeField) && hasRowIntervalType(size) =>
        builder.countWindow(toLong(size))

      case TumblingGroupWindow(_, _, _) =>
        // TODO: EventTimeTumblingGroupWindow should sort the stream on event time
        // before applying the  windowing logic. Otherwise, this would be the same as a
        // ProcessingTimeTumblingGroupWindow
        throw new UnsupportedOperationException(
          "Event-time grouping windows on row intervals are currently not supported.")

      case SlidingGroupWindow(_, timeField, size, slide)
        if isProctimeAttribute(timeField) && hasTimeIntervalType(size) =>
        builder.sliding(toDuration(size), toDuration(slide))
          .withProcessingTime()

      case SlidingGroupWindow(_, timeField, size, slide)
        if isRowtimeAttribute(timeField) && hasTimeIntervalType(size) =>
        builder.sliding(toDuration(size), toDuration(slide))
          .withEventTime(timeIdx)

      case SlidingGroupWindow(_, timeField, size, slide)
        if isProctimeAttribute(timeField) && hasRowIntervalType(size) =>
        builder.countWindow(toLong(size), toLong(slide))

      case SlidingGroupWindow(_, _, _, _) =>
        // TODO: EventTimeTumblingGroupWindow should sort the stream on event time
        // before applying the  windowing logic. Otherwise, this would be the same as a
        // ProcessingTimeTumblingGroupWindow
        throw new UnsupportedOperationException(
          "Event-time grouping windows on row intervals are currently not supported.")

      case SessionGroupWindow(_, timeField, gap)
        if isProctimeAttribute(timeField) =>
        builder.session(toDuration(gap)).withProcessingTime()

      case SessionGroupWindow(_, timeField, gap)
        if isRowtimeAttribute(timeField) =>
        builder.session(toDuration(gap)).withEventTime(timeIdx)
    }

    if (emitStrategy.isDefined && emitStrategy.get.produceUpdates) {
      // mark this operator will send retraction and set new trigger
      newBuilder
        .withSendRetraction()
        .triggering(emitStrategy.get.getTrigger)
    } else {
      newBuilder
    }
  }

  protected def getWindowClass(window: LogicalWindow): Class[_] = {
    window match {
      case TumblingGroupWindow(_, _, size) if hasRowIntervalType(size) =>
        classOf[CountWindow]
      case SlidingGroupWindow(_, _, size, _) if hasRowIntervalType(size) =>
        classOf[CountWindow]
      case _ => classOf[TimeWindow]
    }
  }

  protected def createWindowOperator(
    config: TableConfig,
    aggCodeGenerator: AggsHandlerCodeGenerator,
    recordEqualiser: GeneratedRecordEqualiser,
    accTypes: Array[LogicalType],
    windowPropertyTypes: Array[LogicalType],
    aggValueTypes: Array[LogicalType],
    inputFields: Seq[LogicalType],
    timeIdx: Int,
    aggInfoList: AggregateInfoList): WindowOperator[_, _]
}
