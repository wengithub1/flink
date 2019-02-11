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

package org.apache.flink.table.expressions;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.SqlTimeTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.typeutils.RowIntervalTypeInfo;
import org.apache.flink.table.typeutils.TimeIntervalTypeInfo;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The literal expression.
 */
@PublicEvolving
public final class Literal implements Expression {

	private final Object value;

	private final Optional<TypeInformation<?>> type;

	public Literal(Object value) {
		this.value = value;
		this.type = Optional.empty();
	}

	public Literal(Object value, TypeInformation<?> type) {
		this.value = value;
		this.type = Optional.of(type);
	}

	public Object getValue() {
		return value;
	}

	public Optional<TypeInformation<?>> getType() {
		return type;
	}

	@Override
	public List<Expression> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public <R> R accept(ExpressionVisitor<R> visitor) {
		return visitor.visitLiteral(this);
	}

	@Override
	public String toString() {
		if (value == null) {
			return "null";
		}

		if (type.isPresent()) {
			if (type.get() instanceof BasicTypeInfo) {
				return value.toString();
			} else if (type.get() == SqlTimeTypeInfo.DATE) {
				return value.toString() + ".toDate";
			} else if (type.get() == SqlTimeTypeInfo.TIME) {
				return value.toString() + ".toTime";
			} else if (type.get() == SqlTimeTypeInfo.TIMESTAMP) {
				return value.toString() + ".toTimestamp";
			} else if (type.get() == TimeIntervalTypeInfo.INTERVAL_MILLIS) {
				return value.toString() + ".millis";
			} else if (type.get() == TimeIntervalTypeInfo.INTERVAL_MONTHS) {
				return value.toString() + ".months";
			} else if (type.get() == RowIntervalTypeInfo.INTERVAL_ROWS) {
				return value.toString() + ".rows";
			} else {
				return "Literal(" + value.toString() + ", " + type.get().toString() + ")";
			}
		} else {
			return "Literal(" + value.toString() + ")";
		}
	}
}