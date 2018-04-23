package core;

import com.google.gson.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class App {
    private static final String BASE_URL = "https://stepik.org/api/courses?page=";
    private static final int THREAD_COUNT = 10;
    private final OkHttpClient client;
    private final Gson gson;
    private final JsonParser jsonParser;
    private final List<Course> courses;
    private final Thread printer;
    private final AtomicInteger cnt;

    private App() {
        client = new OkHttpClient()
                .newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        gson = new GsonBuilder().setPrettyPrinting().create();
        jsonParser = new JsonParser();
        courses = new CopyOnWriteArrayList<>();
        printer = new Thread(() -> {
            StringBuilder builder = new StringBuilder();
            builder.append("\rWaiting.");
            while (!Thread.interrupted()) {
                try {
                    builder.append(".");
                    System.out.print(builder.toString());
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.print("\n");
        });
        cnt = new AtomicInteger();
        cnt.set(0);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(THREAD_COUNT);
        App app = new App();
        ParallelTask task = new ParallelTask(app);
        Future[] futures = new Future[THREAD_COUNT];
        try {
            app.printer.start();
            int n = Integer.parseInt(args[0]);
            for (int i = 0; i < futures.length; i++) {
                Future future = service.submit(task);
                futures[i] = future;
            }

            for (Future future : futures) {
                while (!future.isDone()) {
                    // do nothing
                }
            }
            app.printer.interrupt();
            service.shutdown();
            app.printTop(n);
        } catch (NumberFormatException e) {
            System.err.println("Couldn't parse integer argument.");
        } finally {
            app.printer.interrupt();
            service.shutdown();
        }
    }

    void run() {
        boolean hasNext = true;
        Response response = null;
        while (hasNext) {
            try {
                cnt.incrementAndGet();
                Request request = new Request.Builder()
                        .url(BASE_URL + String.valueOf(cnt.get()))
                        .get()
                        .build();
                response = client.newCall(request).execute();

                Optional<ResponseBody> body = Optional.ofNullable(response.body());
                if (!body.isPresent()) {
                    cnt.incrementAndGet();
                    continue;
                }

                String json = body.get().string();
                JsonObject object = jsonParser.parse(json).getAsJsonObject();

                if (object.get("meta") == null) {
                    break;
                }

                hasNext = object.get("meta").getAsJsonObject().get("has_next").getAsBoolean();
                JsonArray jsonArray = object.get("courses").getAsJsonArray();

                Course course;
                for (JsonElement element : jsonArray) {
                    course = new Course(
                            element.getAsJsonObject().get("id").getAsLong(),
                            element.getAsJsonObject().get("title").getAsString(),
                            element.getAsJsonObject().get("learners_count").getAsInt()
                    );
                    courses.add(course);
                }
                response.close();
            } catch (IOException e) {
                System.err.println("Something wrong.");
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
    }

    private void printTop(int n) {
        List<Course> c = courses.stream().sorted().limit(n).collect(Collectors.toList());
        System.out.println(gson.toJson(c));
    }

    class Course implements Comparable<Course> {
        private long id;
        private String name;
        private int count;

        Course(long id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }

        @Override
        public String toString() {
            return "Course{" +
                    "id=" + id +
                    ", name='" + name + '\'' +
                    ", count=" + count +
                    '}';
        }

        @Override
        public int compareTo(Course o) {
            return o.count - this.count;
        }
    }

    static class ParallelTask implements Runnable {
        private final App app;

        ParallelTask(App app) {
            this.app = app;
        }

        @Override
        public void run() {
            app.run();
        }
    }
}
