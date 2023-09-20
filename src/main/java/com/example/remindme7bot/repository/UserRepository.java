package com.example.remindme7bot.repository;

import com.example.remindme7bot.model.User;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<User, Long> {
}
