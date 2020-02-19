/*
 * Copyright 2020 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.hazelcast.azure;

/**
 * Utility methods.
 */
final class Utils {

    private Utils() {
    }

    static boolean isBlank(final String string) {
        return string == null || string.trim().length() == 0;
    }

    static boolean isAllBlank(final String... values) {
        if (values != null) {
            for (final String val : values) {
                if (!isBlank(val)) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean isAllNotBlank(final String... values) {
        if (values == null) {
            return false;
        }
        for (final String val : values) {
            if (isBlank(val)) {
                return false;
            }
        }
        return true;
    }
}
