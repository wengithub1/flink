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

import java.util.ArrayList;
import java.util.List;

/**
 * The distinct aggregate expression, we add this definition to
 * prevent users from continuously calling distinct. e.g.: a.distinct.distinct.
 */
@PublicEvolving
public final class DistinctAgg implements Expression {

	private final Expression child;

	public DistinctAgg(Expression child) {
		this.child = child;
	}

	@Override
	public List<Expression> getChildren() {
		ArrayList children = new ArrayList<Expression>();
		children.add(child);
		return children;
	}

	@Override
	public <R> R accept(ExpressionVisitor<R> visitor) {
		return visitor.visitDistinctAgg(this);
	}
}