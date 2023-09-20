package com.example.remindme7bot.service;

import com.example.remindme7bot.config.BotConfig;
import com.example.remindme7bot.model.User;
import com.example.remindme7bot.repository.TodoRepository;
import com.example.remindme7bot.repository.UserRepository;
import com.vdurmont.emoji.EmojiParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;

    private TodoRepository todoRepository;

    private TodoService todoService;

    private static final String HELP_TEXT = "Список команд:\n" +
            "Команда /start - приветственное сообщение\n" +
            "Команда /new - создать новую задачу\n" +
            "Команда /todo - посмотреть список задач\n" +
            "Команда /notify - настроить уведомления\n" +
            "Чтобы редактировать задачу доcтаточно просто ввести " +
            "её номер в списке. Например /2 (Можно без \"/\")";

    final BotConfig config;

    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start","Начать общение с ботом"));
        listOfCommands.add(new BotCommand("/help","Список комманд"));
        listOfCommands.add(new BotCommand("/new", "Новая задача"));
        listOfCommands.add(new BotCommand("/todo", "Список задач"));
        listOfCommands.add(new BotCommand("/1","Редактировать задачу 1"));
        listOfCommands.add(new BotCommand("/2","Редактировать задачу 2"));
        listOfCommands.add(new BotCommand("/notify","Настроить уведомления"));
        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        }
        catch (TelegramApiException e) {
            log.error("Error setting bot's command list: " + e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    @Override
    public String getBotToken() {
        return config.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            Long chatId = update.getMessage().getChatId();


            switch (messageText) {
                case "/start":
                    registerUser(update.getMessage());
                    startCommandReceived(chatId, update.getMessage().getChat().getFirstName());
                    break;
                case "/help":
                case "help":
                    sendMessage(chatId, HELP_TEXT);
                    break;
                case "/todo":
                case "todo":
                case "Лист":
                    todoCommandReceived();
                    sendMessage(chatId, "TODO лист");
                    break;
                case "/new":
                case "new":
                case "Новая задача":
                    newTodoCommandReceived(chatId);
                    sendMessage(chatId, "Новая задача");
                    break;
                case "/notify":
                case "notify":
                case "Уведомления":
                    sendMessage(chatId, "Настройка уведомлений");
                    break;
                default:
                    try {
                        taskNumberReceived(chatId, Integer.parseInt(messageText.replace("/", "")));
                        log.info("taskNumberReceived by User: " + update.getMessage().getChat().getFirstName());
                    } catch (NumberFormatException ignored) {
                        sendMessage(chatId, "Простите, команда не распознана");
                    }
            }
        }
    }

    private void setDefaultKeyboard(SendMessage message) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        List<KeyboardRow> keyboardRows = new ArrayList<>();

        KeyboardRow row = new KeyboardRow();
        row.add("Лист");
        keyboardRows.add(row);

        row = new KeyboardRow();
        row.add("Новая задача");
        keyboardRows.add(row);

        row = new KeyboardRow();
        row.add("Уведомления");
        keyboardRows.add(row);

        keyboardMarkup.setKeyboard(keyboardRows);
        message.setReplyMarkup(keyboardMarkup);
    }

    private void todoCommandReceived() {

    }

    private void newTodoCommandReceived(Long chatId) {
        Update update = new Update();
        sendMessage(chatId, "Введите название задачи");
        String nameTodo = update.getMessage().getText();
        sendMessage(chatId, "Введите описание задачи");
        String descriptionTodo = update.getMessage().getText();
        todoService.createTodo(nameTodo, descriptionTodo, chatId);
    }

    private void taskNumberReceived(long chatId, Integer num) {
        sendMessage(chatId, "Получен номер задачи: " + num);
        //log.info();
    }

    private void registerUser(Message msg) {
        if (userRepository.findById(msg.getChatId()).isEmpty()) {
            var chatId = msg.getChatId();
            var chat = msg.getChat();

            User user = new User();

            user.setChatId(chatId);
            user.setFirstName(chat.getFirstName());
            user.setLastName(chat.getLastName());
            user.setUserName(chat.getUserName());
            user.setRegisteredAt(new Timestamp(System.currentTimeMillis()));

            userRepository.save(user);
            log.info("user saved: " + user);
        }
    }

    private void startCommandReceived(long chatId, String name) {
        String answer;
        answer = EmojiParser.parseToUnicode(
                "Привет, " + name + "! Я RemindMe7 :zap:\n" +
                        "Я помогу тебе вести свой TODO-лист задач.\n\n" +
                        "Ты будешь создавать задачи :pushpin:, а я буду:\n" +
                        " - следить за их дедлайнами\n" +
                        " - структурировать их по времени\n" +
                        " - напоминать о важных :exclamation:\n"
        );
        sendMessage(chatId, answer);
        answer = EmojiParser.parseToUnicode(
                "Давай создадим твою первую задачу, " +
                        "для этого нажми кнопку «Создать» на клавиатуре " +
                        "или просто введи команду /new."
        );
        sendMessage(chatId, answer);
        log.info("Replied to user " + name);
    }



    private void sendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);

        setDefaultKeyboard(message);

        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("Error occurred: " + e.getMessage());
        }
    }
}
