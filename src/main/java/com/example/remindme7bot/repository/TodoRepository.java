package com.example.remindme7bot.repository;

import com.example.remindme7bot.model.Todo;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface TodoRepository extends CrudRepository<Todo, Long> {
    List<Todo> findAllByUser_ChatId(Long chatId);
}
