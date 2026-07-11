package com.autospec.mapper;

import com.autospec.entity.Project;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    @Select("""
            select distinct p.*
            from project p
            left join project_member pm on pm.project_id = p.id
            where p.user_id = #{userId}
               or pm.user_id = #{userId}
            order by p.id desc
            limit #{limit} offset #{offset}
            """)
    List<Project> selectVisibleProjects(
            @Param("userId") Long userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );
}
