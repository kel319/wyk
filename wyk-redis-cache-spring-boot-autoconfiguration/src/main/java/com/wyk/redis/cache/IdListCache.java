package com.wyk.redis.cache;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * LIST 模式下列表缓存的 value：ID 列表与 total。版本由 prefix:version 控制，key 为 prefix:model:keyPart:md5:version。
 */
@Data
public class IdListCache implements Serializable {
    private List<Long> ids;
    private long total;

    public IdListCache() {}

    public IdListCache(List<Long> ids, long total) {
        this.ids = ids;
        this.total = total;
    }

}
