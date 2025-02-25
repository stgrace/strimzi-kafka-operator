/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource.cruisecontrol;

public class CruiseControlRetriableConnectionException extends RuntimeException {

    public CruiseControlRetriableConnectionException(Throwable cause) {
        super(cause);
    }
}
