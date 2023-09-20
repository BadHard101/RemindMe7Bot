package com.example.remindme7bot.model;

import lombok.Data;

@Data
public class ChatState {
    private boolean expectingName;
    private String Title;
}
