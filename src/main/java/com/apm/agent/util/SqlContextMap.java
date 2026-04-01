package com.apm.agent.util;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public class SqlContextMap {
    // WeakHashMap prevents memory leaks when PreparedStatement objects are garbage collected.
    private static final Map<Object, String> sqlMap = Collections.synchronizedMap(new WeakHashMap<>());

    public static void put(Object statement, String sql) {
        sqlMap.put(statement, sql);
    }

    public static String get(Object statement) {
        return sqlMap.get(statement);
    }
}
