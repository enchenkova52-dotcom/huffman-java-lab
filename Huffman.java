import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class Huffman {
    private static final byte[] MAGIC = {'H', 'U', 'F', '1'};

    private static class Node {
        int symbol;
        long freq;
        Node left;
        Node right;

        Node(int symbol, long freq) {
            this.symbol = symbol;
            this.freq = freq;
        }

        Node(Node left, Node right) {
            this.symbol = -1;
            this.freq = left.freq + right.freq;
            this.left = left;
            this.right = right;
        }

        boolean isLeaf() {
            return left == null && right == null;
        }
    }

    private static class BitWriter implements Closeable {
        private final OutputStream out;
        private int currentByte = 0;
        private int bitsFilled = 0;

        BitWriter(OutputStream out) {
            this.out = out;
        }

        void writeBit(int bit) throws IOException {
            currentByte = (currentByte << 1) | (bit & 1);
            bitsFilled++;

            if (bitsFilled == 8) {
                out.write(currentByte);
                currentByte = 0;
                bitsFilled = 0;
            }
        }

        void writeCode(String code) throws IOException {
            for (int i = 0; i < code.length(); i++) {
                writeBit(code.charAt(i) == '1' ? 1 : 0);
            }
        }

        @Override
        public void close() throws IOException {
            if (bitsFilled > 0) {
                currentByte <<= (8 - bitsFilled);
                out.write(currentByte);
            }

            out.close();
        }
    }

    private static class BitReader {
        private final InputStream in;
        private int currentByte = 0;
        private int bitsRemaining = 0;

        BitReader(InputStream in) {
            this.in = in;
        }

        int readBit() throws IOException {
            if (bitsRemaining == 0) {
                currentByte = in.read();

                if (currentByte == -1) {
                    return -1;
                }

                bitsRemaining = 8;
            }

            int bit = (currentByte >> (bitsRemaining - 1)) & 1;
            bitsRemaining--;

            return bit;
        }
    }

    private static Node buildTree(long[] freq) {
        PriorityQueue<Node> queue = new PriorityQueue<>(
            Comparator
                .comparingLong((Node n) -> n.freq)
                .thenComparingInt(n -> n.symbol)
        );

        for (int i = 0; i < 256; i++) {
            if (freq[i] > 0) {
                queue.add(new Node(i, freq[i]));
            }
        }

        if (queue.isEmpty()) {
            return null;
        }

        while (queue.size() > 1) {
            Node first = queue.poll();
            Node second = queue.poll();

            queue.add(new Node(first, second));
        }

        return queue.poll();
    }

    private static void buildCodes(Node node, String code, String[] codes) {
        if (node == null) {
            return;
        }

        if (node.isLeaf()) {
            codes[node.symbol] = code.isEmpty() ? "0" : code;
            return;
        }

        buildCodes(node.left, code + "0", codes);
        buildCodes(node.right, code + "1", codes);
    }

    private static void encode(String inputFile, String outputFile) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(inputFile));

        long[] freq = new long[256];

        for (byte b : data) {
            freq[b & 0xFF]++;
        }

        Node root = buildTree(freq);

        String[] codes = new String[256];
        buildCodes(root, "", codes);

        try (
            OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(Paths.get(outputFile)));
            DataOutputStream dataOut = new DataOutputStream(fileOut)
        ) {
            dataOut.write(MAGIC);
            dataOut.writeLong(data.length);

            for (int i = 0; i < 256; i++) {
                dataOut.writeLong(freq[i]);
            }

            dataOut.flush();

            try (BitWriter bitWriter = new BitWriter(fileOut)) {
                for (byte b : data) {
                    bitWriter.writeCode(codes[b & 0xFF]);
                }
            }
        }
    }

    private static void decode(String inputFile, String outputFile) throws IOException {
        try (
            InputStream fileIn = new BufferedInputStream(Files.newInputStream(Paths.get(inputFile)));
            DataInputStream dataIn = new DataInputStream(fileIn);
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(Paths.get(outputFile)))
        ) {
            byte[] magic = new byte[4];
            dataIn.readFully(magic);

            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Неверный формат файла");
            }

            long originalSize = dataIn.readLong();

            long[] freq = new long[256];

            for (int i = 0; i < 256; i++) {
                freq[i] = dataIn.readLong();
            }

            Node root = buildTree(freq);

            if (originalSize == 0) {
                return;
            }

            if (root == null) {
                throw new IOException("Некорректный архив");
            }

            if (root.isLeaf()) {
                for (long i = 0; i < originalSize; i++) {
                    out.write(root.symbol);
                }

                return;
            }

            BitReader bitReader = new BitReader(fileIn);
            Node current = root;
            long written = 0;

            while (written < originalSize) {
                int bit = bitReader.readBit();

                if (bit == -1) {
                    throw new IOException("Недостаточно данных для декодирования");
                }

                current = bit == 0 ? current.left : current.right;

                if (current.isLeaf()) {
                    out.write(current.symbol);
                    written++;
                    current = root;
                }
            }
        }
    }

    private static void runTests() throws IOException {
    System.out.println("=== Тест 1: 10 одинаковых символов ===");

    Files.write(
        Paths.get("same10.txt"),
        "1111111111".getBytes(StandardCharsets.US_ASCII)
    );

    encode("same10.txt", "same10.huf");
    decode("same10.huf", "same10_decoded.txt");

    if (Arrays.equals(
            Files.readAllBytes(Paths.get("same10.txt")),
            Files.readAllBytes(Paths.get("same10_decoded.txt"))
    )) {
        System.out.println("Тест 1 пройден: файлы совпадают");
    } else {
        System.out.println("Тест 1 не пройден: файлы отличаются");
    }

    System.out.println();
    System.out.println("=== Тест 2: 20 байт, 3 символа ===");

    Files.write(
        Paths.get("three20.txt"),
        "11111111112222233333".getBytes(StandardCharsets.US_ASCII)
    );

    encode("three20.txt", "three20.huf");
    decode("three20.huf", "three20_decoded.txt");

    if (Arrays.equals(
            Files.readAllBytes(Paths.get("three20.txt")),
            Files.readAllBytes(Paths.get("three20_decoded.txt"))
    )) {
        System.out.println("Тест 2 пройден: файлы совпадают");
    } else {
        System.out.println("Тест 2 не пройден: файлы отличаются");
    }

    System.out.println();
    System.out.println("=== Тест 3: бинарный class-файл ===");

    encode("Huffman.class", "HuffmanClass.huf");
    decode("HuffmanClass.huf", "HuffmanClass_decoded.class");

    if (Arrays.equals(
            Files.readAllBytes(Paths.get("Huffman.class")),
            Files.readAllBytes(Paths.get("HuffmanClass_decoded.class"))
    )) {
        System.out.println("Тест 3 пройден: бинарные файлы совпадают");
    } else {
        System.out.println("Тест 3 не пройден: бинарные файлы отличаются");
    }

    System.out.println();
    System.out.println("=== Все тесты завершены ===");
}

    private static void printUsage() {
    System.out.println("Использование:");
    System.out.println("  java Huffman encode <inputFile> <outputFile>");
    System.out.println("  java Huffman decode <inputFile> <outputFile>");
    System.out.println("  java Huffman test");
}

   public static void main(String[] args) {
    try {
        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            runTests();
            return;
        }

        if (args.length != 3) {
            printUsage();
            return;
        }

        String mode = args[0];
        String inputFile = args[1];
        String outputFile = args[2];

        if (mode.equalsIgnoreCase("encode")) {
            encode(inputFile, outputFile);
            System.out.println("Файл закодирован: " + outputFile);
        }
        else if (mode.equalsIgnoreCase("decode")) {
            decode(inputFile, outputFile);
            System.out.println("Файл декодирован: " + outputFile);
        }
        else {
            printUsage();
        }
    }
    catch (IOException e) {
        System.err.println("Ошибка: " + e.getMessage());
    }
}
}