#include "api.h"
#include <string>
#include <vector>
using namespace std;
#include <vector>
#include <string>
#include <cstring>
#include <cctype>

// --- 1. Метаданные плагина ---

// Имя команды. В чате нужно будет писать: #star текст
extern "C" EXPORT const char* get_name() {
    return "mask";
}

// Описание для меню #help
extern "C" EXPORT const char* get_description() {
    return "Выводит первые две буквы и последние две буквы слова, остальные буквы, если они есть, выводятся звездочками";
}

// --- 2. Вспомогательные функции для UTF-8 ---

// Разбивает строку на отдельные символы (учитывая, что русская буква = 2 байта)
std::vector<std::string> get_utf8_chars(const std::string& str) {
    std::vector<std::string> chars;
    for (size_t i = 0; i < str.length(); ) {
        unsigned char c = str[i];
        int char_len = 1;
        
        if ((c & 0x80) == 0) char_len = 1;        // ASCII
        else if ((c & 0xE0) == 0xC0) char_len = 2; // Кириллица
        else if ((c & 0xF0) == 0xE0) char_len = 3;
        else if ((c & 0xF8) == 0xF0) char_len = 4;
        
        if (i + char_len > str.length()) char_len = 1; 
        
        chars.push_back(str.substr(i, char_len));
        i += char_len;
    }
    return chars;
}

// Проверяет, является ли символ разделителем (пробел, точка, запятая...)
bool is_separator(const std::string& s) {
    if (s.length() > 1) return false; // Буквы (UTF-8) не разделители
    unsigned char c = s[0];
    return std::isspace(c) || std::ispunct(c);
}

// --- 3. Основная логика обработки ---

extern "C" EXPORT const char* handle_message(const char* sender, const char* text) {
    // static нужен, чтобы память не очистилась после возврата из функции
    static std::string result;
    result.clear();

    if (!text || std::strlen(text) == 0) {
        return "Пожалуйста, введите текст после команды #star";
    }

    // 1. Разбиваем текст на символы
    std::string msg = text;
    std::vector<std::string> chars = get_utf8_chars(msg);
    std::vector<std::string> current_word;

    // Лямбда-функция для обработки накопленного слова
    auto process_word = [&]() {
        size_t len = current_word.size();
        if (len == 0) return;

        if (len <= 4) {
            // Короткие слова не меняем
            for (const auto& c : current_word) result += c;
        } else {
            // Длинные слова: 2 буквы + **** + 2 буквы
            result += current_word[0];
            result += current_word[1];
            
            // Заменяем середину на звездочки
            for (size_t k = 2; k < len - 2; k++) {
                result += "*";
            }
            
            result += current_word[len - 2];
            result += current_word[len - 1];
        }
        current_word.clear();
    };

    // 2. Формируем ответ
    // result = std::string(sender) + " пишет: "; // Можно добавить имя автора
    
    for (const auto& ch : chars) {
        if (is_separator(ch)) {
            process_word(); // Обработать слово перед разделителем
            result += ch;   // Добавить сам разделитель
        } else {
            current_word.push_back(ch); // Накапливаем буквы
        }
    }
    process_word(); // Обработать последнее слово

    return result.c_str();
}
