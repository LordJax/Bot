package me.deprilula28.gamesrob.data;

import javafx.util.Pair;
import me.deprilula28.gamesrob.GamesROB;
import me.deprilula28.gamesrob.utility.Log;
import me.deprilula28.gamesrob.utility.Utility;
import me.deprilula28.jdacmdframework.RequestPromise;
import org.postgresql.util.PSQLException;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SQLDatabaseManager {
    private ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);
    private String url;
    private Optional<Pair<String, String>> login = Optional.empty();
    private Connection connection;

    private Connection getConnection() throws SQLException {
        if (login.isPresent()) return DriverManager.getConnection(url, login.get().getKey(), login.get().getValue());
        else return DriverManager.getConnection(url);
    }

    public SQLDatabaseManager(String connectionInfo) {
        String[] attribs = connectionInfo.split(",");
        if (attribs.length < 2) throw new RuntimeException("Required specification of driver class and database path.");
        Log.wrapException("Loading SQL Database", () -> {
            Log.info("SQL database specifications:\nDriver is " + attribs[0] + "\nDatabase is " + attribs[1]);
            Class.forName(attribs[0]);
            this.url = attribs[1];
            if (attribs.length == 4) {
                login = Optional.of(new Pair<>(attribs[2], attribs[3]));
                Log.info("Login is " + attribs[2]);
            }
            connection = getConnection();
            registerTables();
        });
    }

    private Utility.Promise<Void> sqlExecute(String sql, Consumer<PreparedStatement> cons) {
        Utility.Promise<Void> promise = new Utility.Promise<>();
        asyncExecutor.execute(() -> Log.wrapException("SQL Execute " + sql, () -> {
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            cons.accept(preparedStatement);
            Log.trace(preparedStatement);
            preparedStatement.close();

            promise.done(null);
        }));
        return promise;
    }

    private ResultSet sqlQuery(String sql) throws Exception {
        return connection.createStatement().executeQuery(sql);
    }

    public Utility.Promise<Void> save(String table, List<String> keys, String where, Consumer<PreparedStatement> consumer) {
        try {
            ResultSet set = select(table, keys, where);
            if (set.next()) return update(table, keys, where, consumer);
            else return insert(table, keys, consumer);
        } catch (PSQLException ex) {
            Log.trace(ex.getClass().getName() + ": " + ex.getMessage());
            return insert(table, keys, consumer);
        } catch (Exception e) {
            Log.exception("Saving SQL Data", e);
            return null;
        }
    }

    public ResultSet select(String table, List<String> items, String where) throws Exception {
         return sqlQuery(String.format(
                 "SELECT %s FROM %s WHERE %s",
                 items.stream().collect(Collectors.joining(", ")),
                 table, where
         ));
    }

    private Utility.Promise<Void> insert(String table, List<String> keys, Consumer<PreparedStatement> consumer) {
        StringBuilder keysBuilder = new StringBuilder();
        StringBuilder valuesBuilder = new StringBuilder();

        keys.forEach(key -> {
            if (keysBuilder.length() > 0) keysBuilder.append(", ");
            keysBuilder.append(key);

            if (valuesBuilder.length() > 0) valuesBuilder.append(", ");
            valuesBuilder.append("?");
        });

        return sqlExecute(String.format(
                "INSERT INTO %s (%s) VALUES (%s)",
                table, keysBuilder.toString(), valuesBuilder.toString()
        ), statement -> Log.wrapException("Inserting to SQL table", () -> {
            consumer.accept(statement);
            statement.executeUpdate();
        }));
    }

    public Utility.Promise<Void> update(String table, List<String> keys, String where, Consumer<PreparedStatement> consumer) {
        StringBuilder valuesBuilder = new StringBuilder();
        keys.forEach(key -> {
            if (valuesBuilder.length() > 0) valuesBuilder.append(", ");
            valuesBuilder.append(key).append(" = ?");
        });

       return sqlExecute(String.format(
                "UPDATE %s SET %s WHERE %s",
                table, valuesBuilder.toString(), where
        ), statement -> Log.wrapException("Update SQL table", () -> {
           consumer.accept(statement);
           statement.executeUpdate();
       }));
    }

    private void registerTables() {
        asyncExecutor.execute(() -> Log.wrapException("Creating SQL Table", () -> {
            String command = Utility.readResource("/makeTables.sql");
            Statement statement = connection.createStatement();
            statement.executeUpdate(command);
            statement.close();
            Log.trace("Ran make tables command.");
        }));
    }
}