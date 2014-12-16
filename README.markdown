# Transmuter – a transducer with a mutation

## Overview

Transmuters allow complete separation of the transformation pipeline
steps from the concrete evaluation strategy, while keeping intermediate
allocations to a minimum. In that spirit they are closely related to
[transducers](http://blog.cognitect.com/blog/2014/8/6/transducers-are-coming).

Assume for the rest of this document the following require:

    (require '[transmuter.core :as t])
    (require '[transmuter.async :as at])

There are three main components to a transformation pipeline.

### Component 1 – The feed

A *feed* handles the input of the pipeline. It detaches the pipeline
from the actual source. Then it doesn't matter whether the pipeline is a
sequence or an asynchronous channel.

Also a feed may provide a more efficient traversal than a lazy sequence
for eg. vectors or arrays.

As a user of the library you usually don't have any contact to the
feeds directly. They are handled under the hood for you.

### Component 2 – The pipeline steps

The transformation pipeline itself is comprised of so-called
*pipeline steps*. Each step performs a specific task: it transforms the
value in the pipeline, it drops the value or it explodes the value. A
step may also choose to shutdown the entire pipeline.

The steps are chained together to form a pipeline simply by putting the
steps into a vector. Pipelines compose arbitrarily.

### Component 3 – The driver

The *driver* is finally the conductor. It orchestrates the pipeline and
implements a given evaluation strategy. `sequence` returns a lazy
sequence of the input. `transmute` consumes the transformed input
eagerly.

## Example

Let's defined a pipeline:

    (def neighbours (juxt dec identity inc))
    
    (def subpipeline [(t/mapcat neightbours) (t/take 10000)])
    
    (def pipeline
      [(t/map inc)
       (t/drop 15)
       subpipeline
       (t/filter even?)
       t/dedupe])

As you might notice: there is no reference whatsoever to the input or
evaluation strategy. This is purely done by using the corresponding
driver functions.

    (t/sequence pipeline (iterate inc 0))

This produces a lazy sequence of transformed values. Contrary to the
transducer version of this, this sequence is fully lazy in every
circumstance.

    (t/transmute pipeline + (iterate inc 0))

This is an eager aggregation á la `reduce`.

Finally we can just as well create a pipeline between to asynchronous
channels.

    (at/chan input-chan pipeline)

## Usage

Get the latest version from bintray.

Via leiningen:

    (defproject …
      :dependencies [[de.kotka/transmuter 0.1.0]]
      :repositories [["Kotka" "https://dl.bintray.com/kotarak/kotkade"]])

Via gradle:

    repositories {
        maven { url "https://dl.bintray.com/kotarak/kotkade" }
    }
    
    dependencies {
        compile "de.kotka:transmuter:0.1.0"

Via maven:

    <repositories>
      <repository>
        <snapshots><enabled>false</enabled></snapshots>
        <id>kotka</id>
        <name>Kotka Bintray repository</name>
        <url>http://dl.bintray.com/kotarak/kotkade</url>
      </repository>
    </repositories>
    
    <dependency>
      <groupId>de.kotka</groupId>
      <artifactId>transmuter</artifactId>
      <version>0.1.0</version>
    </dependency>
