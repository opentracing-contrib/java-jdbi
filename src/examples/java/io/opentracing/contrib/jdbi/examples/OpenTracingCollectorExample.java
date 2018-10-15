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
package io.opentracing.contrib.jdbi.examples;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.contrib.jdbi.OpenTracingCollector;
import java.util.List;
import java.util.Map;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;

public class OpenTracingCollectorExample {
  private final Handle handle;
  private final Tracer tracer;

  public OpenTracingCollectorExample(Tracer tracer, DBI dbi) {
    this.tracer = tracer;
    dbi.setTimingCollector(new OpenTracingCollector(tracer));
    this.handle = dbi.open();
  }

  public void runExampleQuery() {
    try (Span parent = tracer.buildSpan("parent span").start()) {
      Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
      OpenTracingCollector.setParent(statement, parent);

      // A Span will be created automatically and will reference `parent`.
      List<Map<String, Object>> results = statement.list();
    }
  }

  public static void main(String args[]) throws ClassNotFoundException {
    Class.forName("com.mysql.jdbc.Driver");
    // Real code needs to use a real Tracer implementation here, of course
    Tracer tracer = null;
    DBI dbi = new DBI("jdbc:mysql://localhost/foo", "username", "password");
    OpenTracingCollectorExample example = new OpenTracingCollectorExample(tracer, dbi);
    example.runExampleQuery();
  }
}
