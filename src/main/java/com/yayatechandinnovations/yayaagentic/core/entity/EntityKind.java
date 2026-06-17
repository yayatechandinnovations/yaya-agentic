package com.yayatechandinnovations.yayaagentic.core.entity;

/**
 * Cardinality of a captured entity under one logical name. {@link #SINGLETON}
 * triggers conflict resolution when a second value arrives;
 * {@link #COLLECTION} appends each new value to a list under that name.
 */
public enum EntityKind {
    SINGLETON,
    COLLECTION
}
