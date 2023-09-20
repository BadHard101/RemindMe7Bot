package com.example.remindme7bot.repository;

import com.example.remindme7bot.model.Todo;
import org.springframework.data.repository.CrudRepository;

public interface TodoRepository extends CrudRepository<Todo, Long> {
}
