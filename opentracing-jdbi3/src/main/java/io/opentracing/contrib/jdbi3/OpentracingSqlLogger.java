/*
 * Copyright 2016-2018 The OpenTracing Authors
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
import io.opentracing.tag.Tags;
import java.time.temporal.ChronoUnit;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * OpentracingSqlLogger is a Jdbi SqlLogger that creates OpenTracing Spans for each Jdbi SQLStatement.
 *
 * <p>Example usage:
 * <pre>{@code
 * io.opentracing.Tracer tracer = ...;
 * Jdbi dbi = ...;
 *
 * // One time only: bind OpenTracing to the Jdbi instance as a SqlLogger.
 * dbi.setSqlLogger(new OpentracingSqlLogger(tracer));
 *
 * // Elsewhere, anywhere a `Handle` is available:
 * Handle handle = ...;
 * Span parentSpan = ...;  // optional
 *
 * // Create statements as usual with your `handle` instance.
 *  Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 *
 * // If a parent Span is available, establish the relationship via setParent.
 * OpentracingSqlLogger.setParent(statement, parent);
 *
 * // Use Jdbi as per usual, and Spans will be created for every SQLStatement automatically.
 * List<Map<String, Object>> results = statement.mapToMap().list();
 * }</pre>
 */
@SuppressWarnings("WeakerAccess")
public class OpentracingSqlLogger implements SqlLogger {
  public final static String PARENT_SPAN_ATTRIBUTE_KEY = "io.opentracing.parent";
  static final String COMPONENT_NAME = "java-jdbi";

  private final Tracer tracer;
  private final SpanDecorator spanDecorator;
  private final ActiveSpanSource activeSpanSource;
  private final SqlLogger next;

  public OpentracingSqlLogger(Tracer tracer) {
    this(tracer, SpanDecorator.DEFAULT);
  }

  /**
   * @param tracer the OpenTracing tracer to trace Jdbi calls.
   * @param next a timing collector to "chain" to. When collect is called on
   * this SqlLogger, collect will also be called on 'next'
   */
  public OpentracingSqlLogger(Tracer tracer, SqlLogger next) {
    this(tracer, SpanDecorator.DEFAULT, null, next);
  }

  public OpentracingSqlLogger(Tracer tracer, SpanDecorator spanDecorator) {
    this(tracer, spanDecorator, null);
  }

  public OpentracingSqlLogger(Tracer tracer, ActiveSpanSource spanSource) {
    this(tracer, SpanDecorator.DEFAULT, spanSource);
  }

  public OpentracingSqlLogger(Tracer tracer, SpanDecorator spanDecorator,
                              ActiveSpanSource activeSpanSource) {
    this(tracer, spanDecorator, activeSpanSource, null);
  }

  /**
   * @param tracer the OpenTracing tracer to trace Jdbi calls.
   * @param spanDecorator the SpanDecorator used to name and decorate spans.
   * @param activeSpanSource a source that can provide the currently active
   * span when creating a child span.
   * @param next a timing collector to "chain" to. When logAfterExecution is called on
   * this SqlLogger, logAfterExecution will also be called on 'next'
   * @see ActiveSpanSource
   */
  public OpentracingSqlLogger(Tracer tracer, SpanDecorator spanDecorator,
                              ActiveSpanSource activeSpanSource, SqlLogger next) {
    this.tracer = tracer;
    this.spanDecorator = spanDecorator;
    this.activeSpanSource = activeSpanSource;
    this.next = next;
  }

  @Override
  public void logAfterExecution(StatementContext statementContext) {
    long nowMicros = System.currentTimeMillis() * 1000L;
    Tracer.SpanBuilder builder = tracer
        .buildSpan(spanDecorator.generateOperationName(statementContext))
        .withTag(Tags.COMPONENT.getKey(), COMPONENT_NAME)
        .withTag(Tags.DB_STATEMENT.getKey(), statementContext.getRawSql())
        .withStartTimestamp(nowMicros - (statementContext.getElapsedTime(ChronoUnit.MICROS)));
    Span parent = (Span) statementContext.getAttribute(PARENT_SPAN_ATTRIBUTE_KEY);
    if (parent == null && this.activeSpanSource != null) {
      parent = this.activeSpanSource.activeSpan(statementContext);
    }
    if (parent != null) {
      builder = builder.asChildOf(parent);
    }
    Span collectSpan = builder.start();
    spanDecorator.decorateSpan(collectSpan, statementContext);
    collectSpan.finish(nowMicros);

    if (next != null) {
      next.logAfterExecution(statementContext);
    }
  }


  /**
   * Establish an explicit parent relationship for the (child) Span associated with a
   * SqlStatement.
   *
   * @param statement the Jdbi SqlStatement which will act as the child of `parent`
   * @param parent the parent Span for `statement`
   */
  public static void setParent(SqlStatement<?> statement, Span parent) {
    statement.getContext().define(PARENT_SPAN_ATTRIBUTE_KEY, parent);
  }

}
