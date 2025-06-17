package org.example;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.ArrayList;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Класс ImageProcessor - обработчик изображений с поддержкой многопоточной обработки
 * и возможностью отмены операций.
 * 
 * Поддерживаемые операции:
 * - Растяжение изображения (/s)
 * - Создание негатива (/n)
 * - Удаление файла (/r)
 * - Копирование файла (/c)
 * 
 * Асимптотическая сложность основных операций:
 * - Обработка одного изображения: O(width * height)
 * - Параллельная обработка: O((width * height) / processors)
 * - Обход директории: O(n), где n - количество файлов
 */
public class ImageProcessor {
    // Множество поддерживаемых расширений изображений
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".bmp");
    
    // Атомарная переменная для безопасной отмены операций между потоками
    private static final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Точка входа в программу
     * Асимптотическая сложность: O(n), где n - количество аргументов командной строки
     * 
     * @param args аргументы командной строки:
     *             args[0] - путь к исходной директории
     *             args[1..n] - флаги и параметры операций
     */
    public static void main(String[] args) {
        // Валидация количества аргументов
        if (args.length < 2 || args.length > 4) {
            printUsage();
            return;
        }

        // Инициализация переменных для хранения параметров
        String sourceDirPath = args[0];
        boolean recursive = false;
        String operation = null;
        double scaleFactor = 0.0;
        String targetDirPath = null;

        // Парсинг аргументов командной строки
        // Сложность: O(n), где n - количество аргументов
        for (int i = 1; i < args.length; i++) {
            String arg = args[i].toLowerCase();
            if (arg.equals("/sub")) {
                recursive = true;
            } else if (arg.equals("/s")) {
                validateOperation(operation);
                operation = "/s";
                if (i + 1 >= args.length) {
                    System.err.println("Ошибка: Не указан коэффициент растяжения для /s.");
                    printUsage();
                    return;
                }
                try {
                    scaleFactor = parseScaleFactor(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Ошибка: Неверный формат коэффициента растяжения.");
                    return;
                }
            } else if (arg.equals("/n")) {
                validateOperation(operation);
                operation = "/n";
            } else if (arg.equals("/r")) {
                validateOperation(operation);
                operation = "/r";
            } else if (arg.equals("/c")) {
                validateOperation(operation);
                operation = "/c";
                if (i + 1 >= args.length) {
                    System.err.println("Ошибка: Не указан целевой каталог для /c.");
                    printUsage();
                    return;
                }
                targetDirPath = args[++i];
            } else {
                System.err.println("Ошибка: Неизвестный аргумент: " + arg);
                printUsage();
                return;
            }
        }

        // Проверка наличия операции
        if (operation == null) {
            System.err.println("Ошибка: Не указан флаг операции (/s, /n, /r или /c).");
            printUsage();
            return;
        }

        // Валидация исходной директории
        File sourceDir = validateSourceDirectory(sourceDirPath);
        if (sourceDir == null) return;

        // Валидация целевой директории для операции копирования
        File targetDir = null;
        if (operation.equals("/c")) {
            targetDir = validateTargetDirectory(targetDirPath);
            if (targetDir == null) return;
        }

        // Запуск потока для отслеживания нажатия Esc
        // Сложность: O(1) для инициализации, O(1) для каждой проверки
        Thread escListener = startEscListener();

        // Создание пула потоков для параллельной обработки
        // Количество потоков равно количеству доступных процессоров
        ExecutorService executor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors());

        try {
            // Запуск обработки директории
            // Сложность: O(n * m), где n - количество файлов, m - сложность обработки одного файла
            processDirectory(sourceDir, recursive, operation, scaleFactor, targetDir, executor);
            
            // Ожидание завершения всех задач
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            System.err.println("Ошибка: Обработка прервана: " + e.getMessage());
        } finally {
            // Принудительное завершение, если не все задачи выполнены
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Валидация операции
     * Сложность: O(1)
     */
    private static void validateOperation(String operation) {
        if (operation != null) {
            System.err.println("Ошибка: Указан более одного флага операции.");
            printUsage();
            System.exit(1);
        }
    }

    /**
     * Парсинг коэффициента растяжения
     * Сложность: O(1)
     */
    private static double parseScaleFactor(String arg) {
        double factor = Double.parseDouble(arg);
        if (factor <= 0) {
            System.err.println("Ошибка: Коэффициент растяжения должен быть положительным.");
            System.exit(1);
        }
        return factor;
    }

    /**
     * Валидация исходной директории
     * Сложность: O(1)
     */
    private static File validateSourceDirectory(String path) {
        File dir = new File(path);
        if (!dir.isDirectory() || !dir.exists()) {
            System.err.println("Ошибка: Исходный каталог не существует или не является каталогом.");
            return null;
        }
        return dir;
    }

    /**
     * Валидация целевой директории
     * Сложность: O(1)
     */
    private static File validateTargetDirectory(String path) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("Ошибка: Не удалось создать целевой каталог.");
            return null;
        }
        if (!dir.isDirectory()) {
            System.err.println("Ошибка: Целевой путь не является каталогом.");
            return null;
        }
        return dir;
    }

    /**
     * Запуск потока для отслеживания нажатия Esc
     * Сложность: O(1) для инициализации
     */
    private static Thread startEscListener() {
        Thread escListener = new Thread(() -> {
            try {
                while (true) {
                    if (System.in.available() > 0 && System.in.read() == 27) { // Esc = 27
                        cancelled.set(true);
                        System.out.println("Операция отменена пользователем.");
                        break;
                    }
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Ошибка при отслеживании Esc: " + e.getMessage());
            }
        });
        escListener.setDaemon(true);
        escListener.start();
        return escListener;
    }

    /**
     * Вывод справки по использованию программы
     * Сложность: O(1)
     */
    private static void printUsage() {
        System.err.println("Использование: java ImageProcessor <sourceDir> [/sub] [/s <scaleFactor> | /n | /r | /c <targetDir>]");
        System.err.println("Пример: java ImageProcessor /path/to/source /sub /s 2.0");
        System.err.println("Флаги:");
        System.err.println("  /sub: Рекурсивный обход подкаталогов.");
        System.err.println("  /s: Растянуть изображение (задаётся коэффициент > 0).");
        System.err.println("  /n: Построить негативное изображение.");
        System.err.println("  /r: Удалить файл изображения.");
        System.err.println("  /c: Скопировать изображение в целевой каталог.");
    }

    /**
     * Обработка директории
     * Сложность: O(n * m), где n - количество файлов, m - сложность обработки одного файла
     * 
     * @param dir исходная директория
     * @param recursive флаг рекурсивного обхода
     * @param operation тип операции
     * @param scaleFactor коэффициент растяжения
     * @param targetDir целевая директория
     * @param executor пул потоков
     */
    private static void processDirectory(File dir, boolean recursive, String operation,
                                       double scaleFactor, File targetDir, ExecutorService executor) {
        if (cancelled.get()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (cancelled.get()) break;

            if (file.isDirectory() && recursive) {
                // Рекурсивный обход подкаталогов
                processDirectory(file, recursive, operation, scaleFactor, targetDir, executor);
            } else if (file.isFile() && isImageFile(file)) {
                // Асинхронная обработка изображения
                File finalTargetDir = targetDir;
                executor.submit(() -> processImage(file, operation, scaleFactor, finalTargetDir));
            }
        }
    }

    /**
     * Проверка, является ли файл изображением
     * Сложность: O(1)
     */
    private static boolean isImageFile(File file) {
        String extension = getFileExtension(file).toLowerCase();
        return IMAGE_EXTENSIONS.contains(extension);
    }

    /**
     * Получение расширения файла
     * Сложность: O(1)
     */
    private static String getFileExtension(File file) {
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        return lastDot == -1 ? "" : name.substring(lastDot);
    }

    /**
     * Обработка одного изображения
     * Сложность: O(width * height) для операций с изображением
     * 
     * @param file файл изображения
     * @param operation тип операции
     * @param scaleFactor коэффициент растяжения
     * @param targetDir целевая директория
     */
    private static void processImage(File file, String operation, double scaleFactor, File targetDir) {
        if (cancelled.get()) return;

        try {
            switch (operation) {
                case "/s":
                    scaleImage(file, scaleFactor);
                    break;
                case "/n":
                    negateImage(file);
                    break;
                case "/r":
                    if (!file.delete()) {
                        System.err.println("Ошибка: Не удалось удалить файл: " + file.getAbsolutePath());
                    }
                    break;
                case "/c":
                    copyImage(file, targetDir);
                    break;
            }
        } catch (IOException e) {
            System.err.println("Ошибка обработки файла " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /**
     * Растяжение изображения
     * Сложность: O(width * height)
     * 
     * @param file файл изображения
     * @param scaleFactor коэффициент растяжения
     */
    private static void scaleImage(File file, double scaleFactor) throws IOException {
        BufferedImage image = null;
        BufferedImage scaledImage = null;
        Graphics2D g2d = null;
        try {
            // Чтение исходного изображения
            image = ImageIO.read(file);
            if (image == null) {
                throw new IOException("Не удалось прочитать изображение: " + file.getAbsolutePath());
            }

            // Вычисление новых размеров
            int newWidth = (int) (image.getWidth() * scaleFactor);
            int newHeight = (int) (image.getHeight() * scaleFactor);

            // Создание нового изображения
            scaledImage = new BufferedImage(newWidth, newHeight, image.getType());
            g2d = scaledImage.createGraphics();
            
            // Настройка качества масштабирования
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, 
                                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            
            // Растяжение изображения
            g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
            
            // Сохранение результата
            String format = getFileExtension(file).substring(1);
            if (!ImageIO.write(scaledImage, format, file)) {
                throw new IOException("Не удалось сохранить изображение в формате: " + format);
            }
        } finally {
            // Освобождение ресурсов
            if (g2d != null) g2d.dispose();
            if (image != null) image.flush();
            if (scaledImage != null) scaledImage.flush();
        }
    }

    /**
     * Создание негатива изображения
     * Сложность: O(width * height) для последовательной обработки
     *           O((width * height) / processors) для параллельной обработки
     * 
     * @param file файл изображения
     */
    private static void negateImage(File file) throws IOException {
        BufferedImage image = null;
        BufferedImage negatedImage = null;
        try {
            // Чтение исходного изображения
            image = ImageIO.read(file);
            if (image == null) {
                throw new IOException("Не удалось прочитать изображение: " + file.getAbsolutePath());
            }

            int width = image.getWidth();
            int height = image.getHeight();
            negatedImage = new BufferedImage(width, height, image.getType());
            
            // Выбор метода обработки в зависимости от размера изображения
            if (width * height > 1000000) { // Если изображение больше 1M пикселей
                processImageInParallel(image, negatedImage);
            } else {
                processImageSequentially(image, negatedImage);
            }
            
            // Сохранение результата
            String format = getFileExtension(file).substring(1);
            if (!ImageIO.write(negatedImage, format, file)) {
                throw new IOException("Не удалось сохранить изображение в формате: " + format);
            }
        } finally {
            // Освобождение ресурсов
            if (image != null) image.flush();
            if (negatedImage != null) negatedImage.flush();
        }
    }

    /**
     * Параллельная обработка изображения
     * Сложность: O((width * height) / processors)
     * 
     * @param source исходное изображение
     * @param target целевое изображение
     */
    private static void processImageInParallel(BufferedImage source, BufferedImage target) {
        int width = source.getWidth();
        int height = source.getHeight();
        int processors = Runtime.getRuntime().availableProcessors();
        int chunkHeight = height / processors;
        
        // Создание пула потоков для параллельной обработки
        ExecutorService executor = Executors.newFixedThreadPool(processors);
        try {
            List<Future<?>> futures = new ArrayList<>();
            
            // Разделение изображения на части для параллельной обработки
            for (int i = 0; i < processors; i++) {
                final int startY = i * chunkHeight;
                final int endY = (i == processors - 1) ? height : (i + 1) * chunkHeight;
                
                // Создание задачи для обработки части изображения
                futures.add(executor.submit(() -> {
                    for (int x = 0; x < width; x++) {
                        for (int y = startY; y < endY; y++) {
                            int rgb = source.getRGB(x, y);
                            int r = 255 - (rgb >> 16 & 0xFF);
                            int g = 255 - (rgb >> 8 & 0xFF);
                            int b = 255 - (rgb & 0xFF);
                            int negatedRgb = (r << 16) | (g << 8) | b;
                            target.setRGB(x, y, negatedRgb);
                        }
                    }
                }));
            }
            
            // Ожидание завершения всех задач
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Ошибка при параллельной обработке изображения", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Последовательная обработка изображения
     * Сложность: O(width * height)
     * 
     * @param source исходное изображение
     * @param target целевое изображение
     */
    private static void processImageSequentially(BufferedImage source, BufferedImage target) {
        int width = source.getWidth();
        int height = source.getHeight();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = source.getRGB(x, y);
                int r = 255 - (rgb >> 16 & 0xFF);
                int g = 255 - (rgb >> 8 & 0xFF);
                int b = 255 - (rgb & 0xFF);
                int negatedRgb = (r << 16) | (g << 8) | b;
                target.setRGB(x, y, negatedRgb);
            }
        }
    }

    /**
     * Копирование изображения
     * Сложность: O(1) для операции копирования
     * 
     * @param file исходный файл
     * @param targetDir целевая директория
     */
    private static void copyImage(File file, File targetDir) throws IOException {
        Path sourcePath = file.toPath();
        Path targetPath = new File(targetDir, file.getName()).toPath();
        try {
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("Ошибка при копировании файла " + file.getName() + ": " + e.getMessage(), e);
        }
    }
}