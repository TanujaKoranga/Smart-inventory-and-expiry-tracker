import java.sql.*;

public class DBConnection {
    private static final String URL = "jdbc:mysql://localhost:3306/inventory_db"; 
    private static final String USER = "root";
    private static final String PASSWORD = "tanujamysql.123"; // change if needed

    public static Connection getConnection() {
        Connection conn = null;
        try {
            conn = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (SQLException e) {
            System.out.println("Database connection failed: " + e.getMessage());
        }
        return conn;
    }
}

