#pragma once

#if defined(_WIN32) || defined(_WIN64)
    #define EXPORT __declspec(dllexport)
#else
    #define EXPORT
#endif

extern "C" {
    // Основная логика: принимает отправителя и текст, который шел после команды
    EXPORT const char* handle_message(const char* sender, const char* text);

    // Возвращает имя команды (без #)
    EXPORT const char* get_name();

    // Возвращает описание для #help
    EXPORT const char* get_description();
}