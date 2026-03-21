package com.wyk.redis.cache;

import java.util.*;

/**
 * 级联删除结果：多个 prefix 下要删除的 id 列表。
 * 框架会执行 DEL prefix:id 对每个 (prefix, id)。
 */
public final class CascadeResult {

    private final Map<String, List<Long>> deletes;

    private CascadeResult(Map<String, List<Long>> deletes) {
        this.deletes = deletes != null ? new HashMap<>(deletes) : new HashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CascadeResult empty() {
        return new CascadeResult(Collections.emptyMap());
    }

    /** 各前缀下要删除的 id 列表 */
    public Map<String, List<Long>> getDeletes() {
        return Collections.unmodifiableMap(deletes);
    }

    public static final class Builder {
        private final Map<String, List<Long>> deletes = new LinkedHashMap<>();

        /** 添加要删除的 prefix:id 列表 */
        public Builder delete(String prefix, List<Long> ids) {
            if (prefix != null && ids != null && !ids.isEmpty()) {
                deletes.computeIfAbsent(prefix, k -> new ArrayList<>()).addAll(ids);
            }
            return this;
        }

        public Builder delete(String prefix, Long... ids) {
            if (prefix != null && ids != null && ids.length > 0) {
                deletes.computeIfAbsent(prefix, k -> new ArrayList<>()).addAll(Arrays.asList(ids));
            }
            return this;
        }

        public CascadeResult build() {
            return new CascadeResult(deletes);
        }
    }
}
