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
import org.jdbi.v3.core.statement.StatementContext;

/**
 * An abstract API that allows the OpentracingSqlLogger to customize how parent Spans are
 * discovered.
 * <p>
 * For instance, if Spans are stored in a thread-local variable, an ActiveSpanSource could access
 * them like so:
 * <p>Example usage:
 * <pre>{@code
 * public class SomeClass {
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal<Span> activeSpan =
 *         new ThreadLocal<Span>() {
 *             protected Integer initialValue() {
 *                 return null;
 *             }
 *         };
 * };
 *
 * ... elsewhere ...
 * ActiveSpanSource spanSource = new ActiveSpanSource() {
 *     public Span activeSpan(StatementContext ctx) {
 *         // (In this example we ignore `ctx` entirely)
 *         return activeSpan.get();
 *     }
 * };
 * OpentracingSqlLogger otColl = new OpentracingSqlLogger(tracer, spanSource);
 * ...
 * }</pre>
 */
public interface ActiveSpanSource {
  /**
   * Get the active Span (to use as a parent for any Jdbi Spans). Implementations may or may not
   * need to refer to the StatementContext.
   *
   * @param ctx the StatementContext that needs to be collected and traced
   * @return the currently active Span (for this thread, etc), or null if no such Span could be
   * found.
   */
  Span activeSpan(StatementContext ctx);
}
