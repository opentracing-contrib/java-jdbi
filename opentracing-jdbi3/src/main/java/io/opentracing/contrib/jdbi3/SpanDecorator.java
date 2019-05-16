/*
 * Copyright 2016-2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.jdbi3;

import io.opentracing.Span;
import io.opentracing.Tracer;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * SpanDecorator allows the OpentracingSqlLogger user to control the precise naming and decoration
 * of OpenTracing Spans emitted by the collector.
 *
 * @see OpentracingSqlLogger#OpentracingSqlLogger(Tracer, SpanDecorator)
 */
public interface SpanDecorator {

  SpanDecorator DEFAULT = new SpanDecorator() {

    public String generateOperationName(StatementContext ctx) {
      return "Jdbi Statement";
    }

    @Override
    public void decorateSpan(Span jdbiSpan, StatementContext ctx) {
    }
  };

  /**
   * Transform an Jdbi StatementContext into an OpenTracing Span operation name.
   *
   * @param ctx the StatementContext passed to SqlLogger.logAfterExecution()
   * @return an operation name suitable for the associated OpenTracing Span
   */
  String generateOperationName(StatementContext ctx);

  /**
   * Decorate the given span with additional tags or logs. Implementations may or may not need to
   * refer to the StatementContext.
   *
   * @param jdbiSpan the Jdbi Span to decorate (before `finish` is called)
   * @param ctx the StatementContext passed to SqlLogger.logAfterExecution()
   */
  void decorateSpan(Span jdbiSpan, StatementContext ctx);
}

