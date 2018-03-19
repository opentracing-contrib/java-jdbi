[![Build Status][ci-img]][ci] [![Released Version][maven-img]][maven]

# OpenTracing JDBI Instrumentation
OpenTracing instrumentation for JDBI.

## Installation

pom.xml
```xml
<dependency>
    <groupId>io.opentracing.contrib</groupId>
    <artifactId>jdbi-opentracing</artifactId>
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
// OpenTracingCollector is a JDBI TimingCollector that creates OpenTracing Spans for each JDBI SQLStatement.
dbi.setTimingCollector(new OpenTracingCollector(tracer));
 
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

[ci-img]: https://travis-ci.org/opentracing-contrib/java-jdbi.svg?branch=master
[ci]: https://travis-ci.org/opentracing-contrib/java-jdbi
[maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/jdbi-opentracing.svg
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cjdbi-opentracing
