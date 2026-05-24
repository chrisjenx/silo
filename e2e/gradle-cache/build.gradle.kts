/*
 * One cacheable task (Java compilation) is enough to exercise PUT + GET
 * against Silo via the Gradle build-cache protocol.
 */
plugins {
    java
}
