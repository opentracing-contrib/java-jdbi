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
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.TimingCollector;

/**
 * OpentracingTimingCollector is a JDBI TimingCollector that creates OpenTracing Spans for each JDBI
 * SQLStatement.
 * <p>
 * <p>Example usage:
 * <pre>{@code
 * io.opentracing.Tracer tracer = ...;
 * DBI dbi = ...;
 *
 * // One time only: bind OpenTracing to the DBI instance as a TimingCollector.
 * dbi.setTimingCollector(new OpentracingTimingCollector(tracer));
 *
 * // Elsewhere, anywhere a `Handle` is available:
 * Handle handle = ...;
 * Span parentSpan = ...;  // optional
 *
 * // Create statements as usual with your `handle` instance.
 *  Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 *
 * // If a parent Span is available, establish the relationship via setParent.
 * OpentracingTimingCollector.setParent(statement, parent);
 *
 * // Use JDBI as per usual, and Spans will be created for every SQLStatement automatically.
 * List<Map<String, Object>> results = statement.list();
 * }</pre>
 *
 * @see OpentracingSqlLogger
 * @deprecated Please use the {@code OpentracingSqlLogger} instead if you have JDBI version 3.2 or greater.
 */
@Deprecated
@SuppressWarnings("WeakerAccess")
public class OpentracingTimingCollector implements TimingCollector {
  public final static String PARENT_SPAN_ATTRIBUTE_KEY = "io.opentracing.parent";

  private final Tracer tracer;
  private final SpanDecorator spanDecorator;
  private final ActiveSpanSource activeSpanSource;
  private final TimingCollector next;

  public OpentracingTimingCollector(Tracer tracer) {
    this(tracer, SpanDecorator.DEFAULT);
  }

  /**
   * @param tracer the OpenTracing tracer to trace JDBI calls.
   * @param next a timing collector to "chain" to. When collect is called on
   * this TimingCollector, collect will also be called on 'next'
   */
  public OpentracingTimingCollector(Tracer tracer, TimingCollector next) {
    this(tracer, SpanDecorator.DEFAULT, null, next);
  }

  public OpentracingTimingCollector(Tracer tracer, SpanDecorator spanDecorator) {
    this(tracer, spanDecorator, null);
  }

  public OpentracingTimingCollector(Tracer tracer, ActiveSpanSource activeSpanSource) {
    this(tracer, SpanDecorator.DEFAULT, activeSpanSource);
  }

  public OpentracingTimingCollector(Tracer tracer, SpanDecorator spanDecorator,
      ActiveSpanSource activeSpanSource) {
    this(tracer, spanDecorator, activeSpanSource, null);
  }

  /**
   * @param tracer the OpenTracing tracer to trace JDBI calls.
   * @param spanDecorator the SpanDecorator used to name and decorate spans.
   * @param activeSpanSource a source that can provide the currently active
   * span when creating a child span.
   * @param next a timing collector to "chain" to. When collect is called on
   * this TimingCollector, collect will also be called on 'next'
   * @see SpanDecorator
   * @see ActiveSpanSource
   */
  public OpentracingTimingCollector(Tracer tracer, SpanDecorator spanDecorator,
      ActiveSpanSource activeSpanSource, TimingCollector next) {
    this.tracer = tracer;
    this.spanDecorator = spanDecorator;
    this.activeSpanSource = activeSpanSource;
    this.next = next;
  }

  public void collect(long elapsedNanos, StatementContext statementContext) {
    long nowMicros = System.currentTimeMillis() * 1000;
    Tracer.SpanBuilder builder = tracer
        .buildSpan(spanDecorator.generateOperationName(statementContext))
        .withTag(Tags.COMPONENT.getKey(), "java-jdbi")
        .withTag(Tags.DB_STATEMENT.getKey(), statementContext.getRawSql())
        .withStartTimestamp(nowMicros - (elapsedNanos / 1000));
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
      next.collect(elapsedNanos, statementContext);
    }
  }

  /**
   * Establish an explicit parent relationship for the (child) Span associated with a
   * SQLStatement.
   *
   * @param statement the JDBI SQLStatement which will act as the child of `parent`
   * @param parent the parent Span for `statement`
   */
  public static void setParent(SqlStatement<?> statement, Span parent) {
    statement.getContext().define(PARENT_SPAN_ATTRIBUTE_KEY, parent);
  }

}
