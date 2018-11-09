# OpenTracing JDBI Instrumentation
OpenTracing instrumentation for JDBI v2.

## Installation

[![Released Version][maven-img]][maven]

pom.xml
```xml
<dependency>
    <groupId>io.opentracing.contrib</groupId>
    <artifactId>opentracing-jdbi</artifactId>
    <version>VERSION</version>
</dependency>
```

## Usage

```java
// Instantiate tracer
Tracer tracer = ...;

// Instatiate DBI
DBI dbi = ...;

// One time only: bind OpenTracing to the DBI instance as a TimingCollector.  
// OpenTracingCollector is a Jdbi SqlLogger that creates OpenTracing Spans for each Jdbi SqlStatement.
dbi.setTimingCollector(new OpenTracingCollector(tracer)); //io.opentracing.contrib.**jdbi**.OpenTracingCollector
 
// Elsewhere, anywhere a `Handle` is available:
Handle handle = ...;
Span parentSpan = ...;  // optional
 
// Create statements as usual with your `handle` instance.
Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 
// If a parent Span is available, establish the relationship via setParent.
OpenTracingCollector.setParent(statement, parent);
 
// Use JDBI as per usual, and Spans will be created for every SQLStatement automatically.
List<Map<String, Object>> results = statement.list();
```

## License

[Apache 2.0 License](./LICENSE).

[ci-img]: https://travis-ci.org/opentracing-contrib/java-jdbi.svg?branch=master
[ci]: https://travis-ci.org/opentracing-contrib/java-jdbi
[cov-img]: https://coveralls.io/repos/github/opentracing-contrib/java-jdbi/badge.svg?branch=master
[cov]: https://coveralls.io/github/opentracing-contrib/java-jdbi?branch=master
[maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/jdbi-opentracing.svg
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cjdbi-opentracing
