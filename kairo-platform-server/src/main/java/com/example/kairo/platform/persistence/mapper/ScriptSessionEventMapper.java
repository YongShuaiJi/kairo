package com.example.kairo.platform.persistence.mapper;

import com.example.kairo.platform.script.ScriptSessionEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persistence for {@link ScriptSessionEvent} rows (per-session state-change history). */
public interface ScriptSessionEventMapper {

    int insert(ScriptSessionEvent event);

    List<ScriptSessionEvent> listBySession(@Param("sessionId") String sessionId);
}
