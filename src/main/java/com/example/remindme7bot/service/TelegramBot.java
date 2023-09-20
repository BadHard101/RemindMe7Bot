package com.example.remindme7bot.service;

import com.example.remindme7bot.config.BotConfig;
import com.example.remindme7bot.model.ChatState;
import com.example.remindme7bot.model.Todo;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TodoRepository todoRepository;

    @Autowired
    private TodoService todoService;

    @Autowired
    private KeyboardSetups keyboardSetups;

    private Map<Long, ChatState> chatStates = new HashMap<>();


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

            // Получаем состояние чата
            ChatState chatState = chatStates.get(chatId);

            if (chatState != null) { // Если ждали ответ на создание новой задачи
                newTodoCommandReceived2(chatId, chatState, messageText);
                return;
            }


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
                    todoListCommandReceived(chatId);
                    break;
                case "/new":
                case "new":
                case "Новая задача":
                    newTodoCommandReceived1(chatId);
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


    private void taskNumberReceived(long chatId, Integer num) {
        try {
            Todo todo = todoRepository.findBySeqNumber(num);
            String answer = "Задача №" + todo.getSeqNumber() + " :zap:\n\n" +
                    "Название: " + todo.getTitle() + "\n\n" +
                    "Описание: " + todo.getDescription();
            if (todo.getDeadline() != null) answer += "\n\nДедлайн: " + todo.getDeadline();
            answer = EmojiParser.parseToUnicode(answer);
            sendMessage(chatId, answer);

            ChatState chatState = new ChatState();
            chatState.setEditTask(true);
            chatStates.put(chatId, chatState);
            sendMessage(chatId, "Что вы хотите изменить?");
        } catch (Exception e) {
            sendMessage(chatId, "Нет задачи с таким номером. Проверьте /todo");
        }

        //log.info();
    }

    public boolean setDeadlineFromString(Long chatId, Todo todo, String dateString) {
        // Создаем регулярное выражение для проверки даты в формате "yyyy-MM-dd"
        String datePattern = "\\d{4}-\\d{2}-\\d{2}";
        Pattern pattern = Pattern.compile(datePattern);
        Matcher matcher = pattern.matcher(dateString);

        if (matcher.matches()) {
            // Если дата соответствует формату, преобразуем ее в LocalDate
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate deadline = LocalDate.parse(dateString, formatter);
            todo.setDeadline(deadline);
            return true;
        } else {
            // Если дата введена неправильно, отправляем сообщение об ошибке
            // и подсказываем правильный формат
            sendMessage(chatId, "Пожалуйста, введите дату в формате \"yyyy-MM-dd\".");
            return false;
        }
    }

    private void todoListCommandReceived(Long chatId) {
        String answer = "Список задач :pushpin::\n";
        int counter = 1;
        for (Todo todo: todoRepository.findByUser_ChatId(chatId)) {
            todo.setSeqNumber(counter++);
            todoRepository.save(todo);
            answer += todo.getSeqNumber() + ". " + todo.getTitle();
            if (todo.getImportant() != null && todo.getImportant()) {
                answer += ":exclamation:";
            }
            answer += "\n";
        }
        answer = EmojiParser.parseToUnicode(answer);
        sendMessage(chatId, answer);
    }

    private void newTodoCommandReceived1(Long chatId) {
        ChatState chatState = new ChatState();
        chatState.setExpectingName(true);
        chatStates.put(chatId, chatState);
        sendMessage(chatId, "Введите название задачи");
    }

    private void newTodoCommandReceived2(Long chatId, ChatState chatState, String messageText) {
        if (messageText.equals("Отменить")) {
            chatStates.remove(chatId);
            sendMessage(chatId, "Создание задачи отменено");
            return;
        }

        if (chatState.isExpectingName()) {
            // Этот ответ ожидался как название задачи
            chatState.setTitle(messageText);
            chatState.setExpectingName(false);

            // Теперь ожидаем описание задачи
            sendMessage(chatId, "Введите описание задачи");
        } else {
            String title = chatState.getTitle();
            // Создаем задачу, используя название и описание
            todoService.createTodo(title, messageText, chatId);
            // Очищаем состояние чата
            chatStates.remove(chatId);
            sendMessage(chatId, "Задача «" + title + "» создана!");
            log.info("New todo task by: " + userRepository.findById(chatId));
        }
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

        // Получаем состояние чата
        ChatState chatState = chatStates.get(chatId);
        // Если ждали ответ на создание новой задачи

        if (chatState != null && chatState.isEditTask()) {
            keyboardSetups.setEditTaskKeyboard(message);
        } else if (chatState != null) keyboardSetups.setCancelKeyboard(message);
        else keyboardSetups.setDefaultKeyboard(message);



        try {
            execute(message);
        }
        catch (TelegramApiException e) {
            log.error("Error occurred: " + e.getMessage());
        }
    }
}
