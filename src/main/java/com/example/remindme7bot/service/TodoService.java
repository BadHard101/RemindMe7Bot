package com.example.remindme7bot.service;

import com.example.remindme7bot.model.Todo;
import com.example.remindme7bot.model.User;
import com.example.remindme7bot.repository.TodoRepository;
import com.example.remindme7bot.repository.UserRepository;
import org.jvnet.hk2.annotations.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TodoService {

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private UserRepository userRepository;

    public Todo createTodo(String name, String description, Long chatId) {
        User user = userRepository.findById(chatId).get();
        Todo todo = new Todo();
        todo.setTitle(name);
        todo.setDescription(description);
        todo.setUser(user);
        return todoRepository.save(todo);
    }

    public void completeTodo(Long id) {
        todoRepository.deleteById(id);
    }
}
