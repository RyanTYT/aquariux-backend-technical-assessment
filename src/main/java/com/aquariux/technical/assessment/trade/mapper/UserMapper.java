package com.aquariux.technical.assessment.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT EXISTS(SELECT 1 FROM users WHERE id = #{userId})
            """)
    boolean existsById(Long userId);
}
