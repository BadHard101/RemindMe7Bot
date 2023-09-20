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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TelegramBot extends TelegramLongPollingBot {

    private static final String HELP_TEXT = "Список команд:\n" +
            "Команда /start - приветственное сообщение\n" +
            "Команда /new - создать новую задачу\n" +
            "Команда /todo - посмотреть список задач\n" +
            "Команда /notify - настроить уведомления\n" +
            "Чтобы редактировать задачу доcтаточно просто ввести " +
            "её номер в списке. Например /2 (Можно без \"/\")";
    final BotConfig config;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TodoRepository todoRepository;
    @Autowired
    private TodoService todoService;
    @Autowired
    private KeyboardSetups keyboardSetups;
    // состояния чата для принятия ответов на сообщения
    private final Map<Long, ChatState> chatStates = new HashMap<>();

    public TelegramBot(BotConfig config) {
        this.config = config;
        List<BotCommand> listOfCommands = new ArrayList<>();
        listOfCommands.add(new BotCommand("/start", "Начать общение с ботом"));
        listOfCommands.add(new BotCommand("/help", "Список комманд"));
        listOfCommands.add(new BotCommand("/new", "Новая задача"));
        listOfCommands.add(new BotCommand("/todo", "Список задач"));
        listOfCommands.add(new BotCommand("/1", "Редактировать задачу 1"));
        listOfCommands.add(new BotCommand("/2", "Редактировать задачу 2"));
        listOfCommands.add(new BotCommand("/notify", "Настроить уведомления"));
        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
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

            // Если у пользователь должен ответить на что-то и это связано с редактированием задачи
            if (chatState != null && chatState.isEditingTask()) {
                // то проверяем, изменяет ли он название
                if (chatState.isEditingTitle()) {
                    setNewTitle(chatId, chatState.getTaskId(), messageText);
                    sendMessage(chatId, "Название изменено!");
                    todoListCommandReceived(chatId);
                    return;
                }
                // то проверяем, изменяет ли он описание
                if (chatState.isEditingDescription()) {
                    setNewDescription(chatId, chatState.getTaskId(), messageText);
                    sendMessage(chatId, "Описание изменено!");
                    chatState.setEditingDescription(false);
                    taskNumberReceived(chatId, chatState.getTaskId());
                    return;
                }
                // то проверяем, изменяет ли он дедлайн
                if (chatState.isEditingDeadline()) {
                    setDeadlineFromString(chatId, chatState.getTaskId(), messageText);
                    return;
                }

                // Если он только собирается что-то изменить у задачи
                switch (messageText) {
                    case "Название":
                        chatState.setEditingTitle(true);
                        sendMessage(chatId, "Введите новое название");
                        return;
                    case "Описание":
                        chatState.setEditingDescription(true);
                        sendMessage(chatId, "Введите новое описание");
                        return;
                    case "Дедлайн":
                        chatState.setEditingDeadline(true);
                        sendMessage(chatId, "Введите дату дедлайна в формате \"yyyy-mm-dd\"");
                        return;
                    case "Отметить важным":
                        if (makeImportant(chatState.getTaskId()))
                            sendMessage(chatId, "Задача отмечена как важная!");
                        else
                            sendMessage(chatId, "Задача больше не отмечена как важная!");
                        chatStates.remove(chatId);
                        todoListCommandReceived(chatId);
                        return;
                    case "Выполнить":
                        todoRepository.deleteById(chatState.getTaskId());
                        chatStates.remove(chatId);
                        todoListCommandReceived(chatId);
                        return;
                    case "Назад":
                        chatStates.remove(chatId);
                        todoListCommandReceived(chatId);
                        return;
                }

            } else if (chatState != null) { // Если ждали ответ на создание новой задачи
                newTodoCommandReceived2(chatId, chatState, messageText);
                return;
            }

            // базовые команды
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
                    sendMessage(chatId, "Сейчас по Вашему тарифу «Базовый» RemindMe7Bot уведомляет Вас о" +
                            " задачах каждый день в 12:00 за день до дедлайна (для обычной задачи) и за 2 дня и за " +
                            "день до дедлайна (для важной задачи).\nЧтобы менять время и даты уведомлений, " +
                            "пожалуйста, оплатите подписку стоймостью 999₽ по номеру 8(916)119-25-55");
                    break;
                default:
                    // проверяем не хотел ли пользователь изменить какую-то задачу под определенным номером
                    try {
                        taskNumberReceived(chatId, Integer.parseInt(messageText.replace("/", "")));
                        log.info("taskNumberReceived by User: " + update.getMessage().getChat().getFirstName());
                    } catch (NumberFormatException ignored) {
                        sendMessage(chatId, "Простите, команда не распознана");
                    }
            }
        }
    }

    /**
     * Метод установки и сохранения нового названия у задачи
     */
    private void setNewTitle(Long chatId, Long taskId, String title) {
        Todo todo = todoRepository.findById(taskId).get();
        todo.setTitle(title);
        todoRepository.save(todo);
        chatStates.remove(chatId);
    }

    /**
     * Метод установки и сохранения нового описания у задачи
     */
    private void setNewDescription(Long chatId, Long taskId, String description) {
        Todo todo = todoRepository.findById(taskId).get();
        todo.setDescription(description);
        todoRepository.save(todo);
        chatStates.remove(chatId);
    }

    /**
     * Метод установки задачи статуса как важной
     */
    private boolean makeImportant(Long taskId) {
        Todo todo = todoRepository.findById(taskId).get();
        todo.setImportant(!todo.getImportant());
        todoRepository.save(todo);
        return todo.getImportant();
    }

    /**
     * Получение номера определенной задачи из списка (поиск по порядковому номеру)
     */
    private void taskNumberReceived(long chatId, Integer num) {
        try {
            Todo todo = null;
            List<Todo> todos = todoRepository.findAllByUser_ChatId(chatId);
            for (Todo temp_todo : todos) {
                if (temp_todo.getSeqNumber().equals(num)) todo = temp_todo;
            }
            if (todo == null) {
                sendMessage(chatId, "Нет задачи с таким номером. Проверьте /todo");
                return;
            }

            taskNumberReceivedHelp(chatId, todo);
        } catch (Exception e) {
            sendMessage(chatId, "Нет задачи с таким номером. Проверьте /todo");
        }
    }

    /**
     * Получение номера определенной задачи из списка (поиск по ID задачи в БД)
     */
    private void taskNumberReceived(long chatId, Long taskId) {
        try {
            Todo todo = todoRepository.findById(taskId).get();

            taskNumberReceivedHelp(chatId, todo);
        } catch (Exception e) {
            sendMessage(chatId, "Нет задачи с таким номером. Проверьте /todo");
        }
    }

    /**
     * Вспомогательный метод для избежания повторения кода методов "Получения номера определенной задачи"
     */
    private void taskNumberReceivedHelp(Long chatId, Todo todo) {
        String answer = "";
        // если задача "важная", то добавляем эмодци
        if (todo.getImportant()) answer += ":exclamation: Важная з";
        else answer += "З";
        answer += "адача №" + todo.getSeqNumber() + " :pushpin:\n\n" +
                "Название: " + todo.getTitle() + "\n\n" +
                "Описание: " + todo.getDescription();
        if (todo.getDeadline() != null) answer += "\n\nДедлайн: " + todo.getDeadline();
        answer = EmojiParser.parseToUnicode(answer);
        sendMessage(chatId, answer);

        ChatState chatState = new ChatState();
        chatState.setEditingTask(true);
        chatState.setTaskId(todo.getId());
        chatStates.put(chatId, chatState);
        sendMessage(chatId, "Что вы хотите изменить?");
    }

    /**
     * Установка дедлайна
     */
    public void setDeadlineFromString(Long chatId, Long taskId, String dateString) {
        // Отмена установки дедлайна
        if (dateString.equals("Отменить")) {
            chatStates.remove(chatId);
            sendMessage(chatId, "Установка дедлайна отменена");
            taskNumberReceived(chatId, taskId);
            return;
        }

        // Находим задачу
        Todo todo = todoRepository.findById(taskId).get();

        // Создаем регулярное выражение для проверки даты в формате "yyyy-MM-dd"
        String datePattern = "\\d{4}-\\d{2}-\\d{2}";
        Pattern pattern = Pattern.compile(datePattern);
        Matcher matcher = pattern.matcher(dateString);

        if (matcher.matches()) {
            // Если дата соответствует формату, преобразуем ее в LocalDate
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDate deadline;
            try {
                deadline = LocalDate.parse(dateString, formatter);
            } catch (Exception ignore) {
                sendMessage(chatId, "Введите корректную дату");
                return;
            }
            todo.setDeadline(deadline);
            todoRepository.save(todo);
            chatStates.remove(chatId);
            sendMessage(chatId, "Дедлайн задачи установлен!");
            todoListCommandReceived(chatId);
        } else {
            // Если дата введена неправильно, отправляем сообщение об ошибке
            // и подсказываем правильный формат
            sendMessage(chatId, "Пожалуйста, введите дату в формате \"yyyy-mm-dd\".");
        }
    }

    /**
     * Вывод списка задач пользователя
     */
    private void todoListCommandReceived(Long chatId) {
        int counter = 1;
        String answer = "Список задач :zap::\n";

        List<Todo> todos = todoRepository.findAllByUser_ChatId(chatId);

        // Фильтруем задачи с deadline != null и сортируем по полю LocalDateTime
        List<Todo> temp_todos = todos.stream()
                .filter(todo -> todo.getDeadline() != null)
                .sorted(Comparator.comparing(Todo::getDeadline))
                .toList();

        // Сначала выводем те, у которых есть дедлайн (отсортированы по дедлайну)
        for (Todo todo : temp_todos) {
            todo.setSeqNumber(counter++);
            todoRepository.save(todo);
            answer += todo.getSeqNumber() + ". " + todo.getTitle() + " до " + todo.getDeadline();

            // если задача "важная", то добавляем эмодци
            if (todo.getImportant()) {
                answer += ":exclamation:";
            }
            answer += "\n";
        }

        // Теперь те, которые без дедлайна (deadline == null)
        temp_todos = todos.stream()
                .filter(todo -> todo.getDeadline() == null)
                .collect(Collectors.toList());

        for (Todo todo : temp_todos) {
            todo.setSeqNumber(counter++);
            todoRepository.save(todo);
            answer += todo.getSeqNumber() + ". " + todo.getTitle();

            // если задача "важная", то добавляем эмодци
            if (todo.getImportant()) {
                answer += ":exclamation:";
            }
            answer += "\n";
        }

        // Преобразовываем эмодзи
        answer = EmojiParser.parseToUnicode(answer);
        sendMessage(chatId, answer);
    }

    /**
     * Первый этап создания новой задачи
     */
    private void newTodoCommandReceived1(Long chatId) {
        ChatState chatState = new ChatState();
        // Ждем ответ от пользователя (название задачи)
        chatState.setExpectingTitle(true);
        chatStates.put(chatId, chatState);
        sendMessage(chatId, "Введите название задачи");
    }

    /**
     * Второй этап создания новой задачи
     */
    private void newTodoCommandReceived2(Long chatId, ChatState chatState, String messageText) {
        // Если хочет отменить создание новой задачи
        if (messageText.equals("Отменить")) {
            chatStates.remove(chatId);
            sendMessage(chatId, "Создание задачи отменено");
            return;
        }

        if (chatState.isExpectingTitle()) {
            // Этот ответ ожидался как название задачи
            chatState.setTitle(messageText);
            chatState.setExpectingTitle(false);

            // Теперь ожидаем описание задачи
            sendMessage(chatId, "Введите описание задачи");
        } else { // На этот раз получив все данные, создаем новую задачу и сохраняем
            String title = chatState.getTitle();
            // Создаем задачу, используя название и описание
            todoService.createTodo(title, messageText, chatId);
            // Очищаем состояние чата
            chatStates.remove(chatId);
            sendMessage(chatId, "Задача «" + title + "» создана!");
            todoListCommandReceived(chatId);
            log.info("New todo task by: " + userRepository.findById(chatId));
        }
    }

    /**
     * Первичная регестрация пользователя для хранения данных
     */
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

    /**
     * Приветственное сообщение
     */
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

    /**
     * Логика ответов
     */
    private void sendMessage(long chatId, String textToSend) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(textToSend);

        // Получаем состояние чата
        ChatState chatState = chatStates.get(chatId);

        // Если ждали ответ на изменение существующей задачи
        if (chatState != null && chatState.isEditingTask()) {
            // ставим клавиатуру изменений задачи
            keyboardSetups.setEditTaskKeyboard(message);
            // Если ждали ответ на создание новой задачи ставим клавиатуру с отменой действия
        } else if (chatState != null)
            keyboardSetups.setCancelKeyboard(message);
            // иначе дифолтная клавиатура
        else keyboardSetups.setDefaultKeyboard(message);
        // Если изменяется дедлайн, то тоже клавиатура для отмены
        if (chatState != null && chatState.isEditingDeadline()) keyboardSetups.setCancelKeyboard(message);

        // Запускаем отправку подготовленного сообщения и клавиатуры
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Error occurred: " + e.getMessage());
        }
    }

    /*// Храним дату последней проверки дедлайнов, чтобы знать, когда нужно снова проверять
    private LocalDate lastCheckDate = LocalDate.now();*/

    public void checkDeadlines() {
        LocalDate currentDate = LocalDate.now();

        /*// Проверяем, прошло ли уже достаточно времени с момента последней проверки (например, 1 день)
        if (currentDate.isAfter(lastCheckDate.plusDays(1))) {*/
            // Получаем все задачи с дедлайном
            List<Todo> todosWithDeadline = todoRepository.findAllByDeadlineIsNotNull();
            // Проверяем каждую задачу
            for (Todo todo : todosWithDeadline) {
                LocalDate deadline = todo.getDeadline();

                // Если дедлайн через 1 день (обычная задача)
                if (deadline.equals(currentDate.plusDays(1))) {
                    // Отправляем напоминание пользователю
                    Long chatId = todo.getUser().getChatId();
                    sendMessage(chatId, "У вас есть задача «" + todo.getTitle() + "», которая завтра должна быть выполнена!");
                }
                // Если дедлайн через 2 дня (важные задачи)
                if (todo.getImportant() && deadline.equals(currentDate.plusDays(2))) {
                    // Отправляем отдельное уведомление для важных задач
                    Long chatId = todo.getUser().getChatId();
                    sendMessage(chatId, "Внимание! У вас есть важная задача «" + todo.getTitle() + "», которая должна быть выполнена через 2 дня!");
                }
            }

            /*// Обновляем дату последней проверки
            lastCheckDate = currentDate;
        }*/
    }

    // Помечаем метод как запускаемый по расписанию
    @Scheduled(cron = "0 0 12 * * ?") // Запускать ежедневно в 12:00
    public void scheduledCheckDeadlines() {
        checkDeadlines();
    }

}
