import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.sql.*;
import java.time.LocalDate;          // For date validation
import java.util.*;
import java.util.regex.*;

public class WebServer {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // Serve frontend
        server.createContext("/", new StaticFileHandler("web"));

        // API endpoints
        server.createContext("/addProduct", new AddProductHandler());
        server.createContext("/viewProducts", new ViewProductsHandler());
        server.createContext("/expiryAlerts", new ExpiryAlertsHandler());
        server.createContext("/dashboardData", new DashboardDataHandler());
        server.createContext("/generateReport", new ReportHandler());
        server.createContext("/deleteProduct", new DeleteProductHandler()); 

        server.setExecutor(null);
        server.start();
        System.out.println("✅ Smart Inventory Server started at http://localhost:8080");
    }

    // -------------------- Serve HTML Files --------------------
    static class StaticFileHandler implements HttpHandler {
        private final File baseDir;
        StaticFileHandler(String dir) { this.baseDir = new File(dir); }

        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            File file = new File(baseDir, path.substring(1));
            if (!file.exists()) {
                String res = "404 Not Found";
                ex.sendResponseHeaders(404, res.length());
                ex.getResponseBody().write(res.getBytes());
                ex.close();
                return;
            }

            String mime = URLConnection.guessContentTypeFromName(file.getName());
            if (mime == null) mime = "text/html";

            byte[] bytes = new FileInputStream(file).readAllBytes();
            ex.getResponseHeaders().add("Content-Type", mime);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    // -------------------- Add Product (with date validation) --------------------
    static class AddProductHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            String res;
            try (InputStream is = ex.getRequestBody()) {
                String body = new String(is.readAllBytes()).trim();

                String name = getJsonValue(body, "product_name");
                String category = getJsonValue(body, "category");
                String qtyStr = getJsonValue(body, "quantity");
                String priceStr = getJsonValue(body, "price");
                String mfg = getJsonValue(body, "manufacture_date");
                String exp = getJsonValue(body, "expiry_date");
                String supplier = getJsonValue(body, "supplier");

                // Basic required field checks
                if (name == null || name.isEmpty()) {
                    res = "{\"error\":\"Product name is required.\"}";
                    sendJson(ex, res);
                    return;
                }
                if (qtyStr == null || qtyStr.isEmpty()) {
                    res = "{\"error\":\"Quantity is required.\"}";
                    sendJson(ex, res);
                    return;
                }
                if (priceStr == null || priceStr.isEmpty()) {
                    res = "{\"error\":\"Price is required.\"}";
                    sendJson(ex, res);
                    return;
                }
                if (mfg == null || exp == null || mfg.isEmpty() || exp.isEmpty()) {
                    res = "{\"error\":\"Manufacture date and expiry date are required.\"}";
                    sendJson(ex, res);
                    return;
                }

                int qty = Integer.parseInt(qtyStr);
                double price = Double.parseDouble(priceStr);

                // Date validation
                LocalDate mfgDate = LocalDate.parse(mfg);   // yyyy-MM-dd
                LocalDate expDate = LocalDate.parse(exp);

                if (!expDate.isAfter(mfgDate)) {
                    res = "{\"error\":\"Expiry date must be after manufacture date.\"}";
                    sendJson(ex, res);
                    return;
                }

                if (category == null || category.isEmpty())
                    category = autoCategorize(name);

                try (Connection conn = DBConnection.getConnection()) {
                    String sql = "INSERT INTO products (product_name, category, quantity, price, manufacture_date, expiry_date, supplier) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    PreparedStatement ps = conn.prepareStatement(sql);

                    ps.setString(1, name);
                    ps.setString(2, category);
                    ps.setInt(3, qty);
                    ps.setDouble(4, price);
                    ps.setDate(5, java.sql.Date.valueOf(mfgDate));
                    ps.setDate(6, java.sql.Date.valueOf(expDate));
                    ps.setString(7, supplier);

                    ps.executeUpdate();
                }

                res = "{\"success\":true, \"category\":\"" + category + "\"}";
            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- View Products --------------------
    static class ViewProductsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String res;
            try (Connection conn = DBConnection.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM products ORDER BY product_id DESC")) {

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;

                while (rs.next()) {
                    if (!first) sb.append(",");
                    sb.append("{")
                      .append("\"id\":").append(rs.getInt("product_id")).append(",")
                      .append("\"name\":\"").append(rs.getString("product_name")).append("\",")
                      .append("\"category\":\"").append(rs.getString("category")).append("\",")
                      .append("\"quantity\":").append(rs.getInt("quantity")).append(",")
                      .append("\"price\":").append(rs.getDouble("price")).append(",")
                      .append("\"expiry_date\":\"").append(rs.getDate("expiry_date")).append("\",")
                      .append("\"supplier\":\"").append(rs.getString("supplier") == null ? "" : rs.getString("supplier")).append("\"")
                      .append("}");
                    first = false;
                }
                sb.append("]");
                res = sb.toString();

            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- Expiry Alerts --------------------
    static class ExpiryAlertsHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {

            String category = null;

            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("category=")) {
                category = query.split("=")[1];
            }

            String sql;
            if (category == null || category.equalsIgnoreCase("All")) {
                sql = "SELECT * FROM products WHERE expiry_date <= DATE_ADD(CURDATE(), INTERVAL 7 DAY)";
            } else {
                sql = "SELECT * FROM products WHERE category = ? AND expiry_date <= DATE_ADD(CURDATE(), INTERVAL 7 DAY)";
            }

            String res;

            try (Connection conn = DBConnection.getConnection()) {

                PreparedStatement ps = conn.prepareStatement(sql);

                if (category != null && !category.equalsIgnoreCase("All")) {
                    ps.setString(1, category);
                }

                ResultSet rs = ps.executeQuery();

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;

                while (rs.next()) {
                    if (!first) sb.append(",");
                    sb.append("{")
                      .append("\"id\":").append(rs.getInt("product_id")).append(",")
                      .append("\"name\":\"").append(rs.getString("product_name")).append("\",")
                      .append("\"category\":\"").append(rs.getString("category")).append("\",")
                      .append("\"quantity\":").append(rs.getInt("quantity")).append(",")
                      .append("\"price\":").append(rs.getDouble("price")).append(",")
                      .append("\"expiry_date\":\"").append(rs.getDate("expiry_date")).append("\"")
                      .append("}");
                    first = false;
                }

                sb.append("]");
                res = sb.toString();

            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- Dashboard Analytics --------------------
    static class DashboardDataHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String res;
            try (Connection conn = DBConnection.getConnection()) {

                String sql = "SELECT category, SUM(quantity) AS total_qty, SUM(quantity * price) AS total_value FROM products GROUP BY category";
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery();

                StringBuilder sb = new StringBuilder("[");
                boolean first = true;

                while (rs.next()) {
                    if (!first) sb.append(",");
                    sb.append("{")
                      .append("\"category\":\"").append(rs.getString("category")).append("\",")
                      .append("\"quantity\":").append(rs.getInt("total_qty")).append(",")
                      .append("\"value\":").append(rs.getDouble("total_value"))
                      .append("}");
                    first = false;
                }
                sb.append("]");

                res = sb.toString();

            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- Smart Report (Text File) --------------------
    static class ReportHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String res;
            File reportFile = new File("web/InventoryReport.txt");

            try (Connection conn = DBConnection.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM products")) {

                PrintWriter writer = new PrintWriter(reportFile);
                writer.println("SMART INVENTORY REPORT");
                writer.println("======================");

                while (rs.next()) {
                    writer.printf("%-20s | %-10s | Qty: %-5d | Exp: %s%n",
                            rs.getString("product_name"),
                            rs.getString("category"),
                            rs.getInt("quantity"),
                            rs.getDate("expiry_date"));
                }
                writer.close();

                res = "{\"success\":true, \"message\":\"Report generated in web folder.\"}";

            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- DELETE PRODUCT --------------------
    static class DeleteProductHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {

            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }

            String query = ex.getRequestURI().getQuery(); // id=5
            int id = -1;

            if (query != null) {
                for (String part : query.split("&")) {
                    String[] kv = part.split("=");
                    if (kv.length == 2 && kv[0].equals("id")) {
                        id = Integer.parseInt(kv[1]);
                        break;
                    }
                }
            }

            String res;

            if (id == -1) {
                res = "{\"error\":\"Missing or invalid id\"}";
                sendJson(ex, res);
                return;
            }

            try (Connection conn = DBConnection.getConnection()) {

                String sql = "DELETE FROM products WHERE product_id = ?";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setInt(1, id);

                int rows = ps.executeUpdate();

                if (rows > 0)
                    res = "{\"success\":true}";
                else
                    res = "{\"success\":false, \"message\":\"No product found\"}";

            } catch (Exception e) {
                res = "{\"error\":\"" + e.getMessage() + "\"}";
            }

            sendJson(ex, res);
        }
    }

    // -------------------- Helper JSON & Category --------------------
    private static String getJsonValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static void sendJson(HttpExchange ex, String res) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");

        byte[] responseBytes = res.getBytes("UTF-8");

        ex.sendResponseHeaders(200, responseBytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(responseBytes);
        os.flush();
        os.close();
    }

    private static String autoCategorize(String name) {
        String n = name.toLowerCase();
        if (n.contains("milk") || n.contains("butter") || n.contains("cheese")) return "Dairy";
        if (n.contains("bread") || n.contains("cake") || n.contains("biscuit")) return "Bakery";
        if (n.contains("soap") || n.contains("shampoo")) return "Personal Care";
        if (n.contains("juice") || n.contains("cola")) return "Beverages";
        return "General";
    }
}
