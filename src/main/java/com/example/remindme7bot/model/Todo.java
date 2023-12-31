package com.example.remindme7bot.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;

@Entity(name = "todoDataTable")
@Data
public class Todo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Integer seqNumber;
    private String title;
    private String description;
    private Boolean important = false;
    private LocalDate deadline;

    @ManyToOne
    @JoinColumn(name = "user_chat_id")
    private User user;

    public Todo() {
    }
}
