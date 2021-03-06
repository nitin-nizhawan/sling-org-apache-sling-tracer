/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sling.tracer.internal;

class Util {
    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s;
    }

    static String nullSafeString(Object o){
        if (o != null){
            return o.toString();
        }
        return null;
    }

    static String nullSafeTrim(String s){
        if(s == null){
            return "";
        }
        return s.trim();
    }

    /**
     * Counts the number of instances the needle in the haystack.
     * @param haystack the string to count the occurrences of the needle in.
     * @param needle the string to count the number of occurrences of.
     * @return the number of occurences the needle appears in the haystack.
     */
    static int count(final String haystack, final String needle) {
        int count = 0;
        int i = 0;

        while ((i = haystack.indexOf(needle, i)) != -1) {
            i++;
            count++;
        }

        return count;
    }
}
