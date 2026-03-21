package com.wyk.redis.cache;


/**
 * 级联删除解析器：根据主实体 id 返回需要一起删除的「前缀 + id 列表」。
 * 框架会按 prefix:id 精确 DEL，不做 SCAN，删除干净且无性能问题。
 */
public interface CascadeLoader {

    /**
     * 根据主实体 id 加载需要级联删除的缓存 key 维度。
     *
     * @param id 主实体 id（如 userId）
     * @return 各前缀下要删除的 id 列表，框架会执行 DEL prefix:id
     */
    CascadeResult load(Long id);
}
