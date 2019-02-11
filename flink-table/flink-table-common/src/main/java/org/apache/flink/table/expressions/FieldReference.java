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
import org.apache.flink.api.common.typeinfo.TypeInformation;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The field reference expression.
 */
@PublicEvolving
public final class FieldReference implements Expression {
	private final String name;

	private final Optional<TypeInformation<?>> resultType;

	public FieldReference(String name) {
		this.name = name;
		this.resultType = Optional.empty();
	}

	public FieldReference(String name, TypeInformation<?> resultType) {
		this.name = name;
		this.resultType = Optional.of(resultType);
	}

	public String getName() {
		return name;
	}

	public Optional<TypeInformation<?>> getResultType() {
		return resultType;
	}

	@Override
	public List<Expression> getChildren() {
		return Collections.emptyList();
	}

	@Override
	public <R> R accept(ExpressionVisitor<R> visitor) {
		return visitor.visitFieldReference(this);
	}

	@Override
	public String toString() {
		return name;
	}
}