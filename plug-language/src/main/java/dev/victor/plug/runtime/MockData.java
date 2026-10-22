package dev.victor.plug.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockData {

    public static final Map<String, List<Map<String, Object>>> DATA =
            new HashMap<>();

    public static final Map<String, Map<String, Class<?>>> SCHEMAS =
            new HashMap<>();

    static {

        List<Map<String, Object>> users = new ArrayList<>();

        users.add(Map.of(
                "id", 1,
                "name", "Victor",
                "age", 20,
                "email", "victor@gmail.com",
                "balance", 25000,
                "active", true,
                "country", "Ghana",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 2,
                "name", "Dery",
                "age", 21,
                "email", "dery@gmail.com",
                "balance", 18500,
                "active", true,
                "country", "Ghana",
                "role", "student"
        ));

        //special data (Hashmap) for testing and developing the NULL type

        Map<String, Object> alex = new HashMap<>();

        alex.put("id", 3);
        alex.put("name", "Alex");
        alex.put("age", 17);
        alex.put("email", null);
        alex.put("balance", 3200);
        alex.put("active", false);
        alex.put("country", "Ghana");
        alex.put("role", "student");

        users.add(alex);

        //end of speicial Alex NULL data

        users.add(Map.of(
                "id", 4,
                "name", "Michael",
                "age", 25,
                "email", "michael@gmail.com",
                "balance", 42000,
                "active", true,
                "country", "Nigeria",
                "role", "developer"
        ));

        users.add(Map.of(
                "id", 5,
                "name", "Sarah",
                "age", 23,
                "email", "sarah@outlook.com",
                "balance", 15700,
                "active", true,
                "country", "Kenya",
                "role", "designer"
        ));

        users.add(Map.of(
                "id", 6,
                "name", "Daniel",
                "age", 19,
                "email", "daniel@gmail.com",
                "balance", 8700,
                "active", true,
                "country", "Ghana",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 7,
                "name", "Emma",
                "age", 28,
                "email", "emma@yahoo.com",
                "balance", 51000,
                "active", true,
                "country", "Nigeria",
                "role", "developer"
        ));

        users.add(Map.of(
                "id", 8,
                "name", "James",
                "age", 31,
                "email", "james@gmail.com",
                "balance", 68000,
                "active", false,
                "country", "Ghana",
                "role", "manager"
        ));

        users.add(Map.of(
                "id", 9,
                "name", "Linda",
                "age", 22,
                "email", "linda@outlook.com",
                "balance", 12400,
                "active", true,
                "country", "Kenya",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 10,
                "name", "Chris",
                "age", 26,
                "email", "chris@gmail.com",
                "balance", 29500,
                "active", true,
                "country", "Ghana",
                "role", "developer"
        ));

        users.add(Map.of(
                "id", 11,
                "name", "Grace",
                "age", 24,
                "email", "grace@yahoo.com",
                "balance", 9800,
                "active", false,
                "country", "Ghana",
                "role", "designer"
        ));

        users.add(Map.of(
                "id", 12,
                "name", "Samuel",
                "age", 29,
                "email", "samuel@gmail.com",
                "balance", 37500,
                "active", true,
                "country", "Nigeria",
                "role", "manager"
        ));

        users.add(Map.of(
                "id", 13,
                "name", "Jessica",
                "age", 27,
                "email", "jessica@outlook.com",
                "balance", 22100,
                "active", true,
                "country", "Ghana",
                "role", "designer"
        ));

        users.add(Map.of(
                "id", 14,
                "name", "Brian",
                "age", 18,
                "email", "brian@gmail.com",
                "balance", 4500,
                "active", true,
                "country", "Kenya",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 15,
                "name", "Olivia",
                "age", 30,
                "email", "olivia@yahoo.com",
                "balance", 73000,
                "active", true,
                "country", "Nigeria",
                "role", "manager"
        ));

        users.add(Map.of(
                "id", 16,
                "name", "Kevin",
                "age", 21,
                "email", "kevin@gmail.com",
                "balance", 11200,
                "active", false,
                "country", "Ghana",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 17,
                "name", "Rachel",
                "age", 25,
                "email", "rachel@outlook.com",
                "balance", 19800,
                "active", true,
                "country", "Kenya",
                "role", "developer"
        ));

        users.add(Map.of(
                "id", 18,
                "name", "Thomas",
                "age", 32,
                "email", "thomas@gmail.com",
                "balance", 91000,
                "active", true,
                "country", "Ghana",
                "role", "manager"
        ));

        users.add(Map.of(
                "id", 19,
                "name", "Martha",
                "age", 23,
                "email", "martha@yahoo.com",
                "balance", 14300,
                "active", true,
                "country", "Nigeria",
                "role", "student"
        ));

        users.add(Map.of(
                "id", 20,
                "name", "Andrew",
                "age", 27,
                "email", "andrew@gmail.com",
                "balance", 33500,
                "active", false,
                "country", "Ghana",
                "role", "developer"
        ));

        DATA.put("users", users);

        Map<String, Class<?>> userSchema = new HashMap<>();

        userSchema.put("id", Integer.class);
        userSchema.put("name", String.class);
        userSchema.put("age", Integer.class);
        userSchema.put("email", String.class);
        userSchema.put("balance", Integer.class);
        userSchema.put("active", Boolean.class);
        userSchema.put("country", String.class);
        userSchema.put("role", String.class);

        SCHEMAS.put("users", userSchema);

        /*
         * orders — 12 orders keyed to user ids plus one row
         * whose user_id is null, covering the inner-join
         * drop of null keys. Users 3, 6, 7, 11, 14, 16, 17,
         * 19 and 20 intentionally have no orders.
         */
        List<Map<String, Object>> orders = new ArrayList<>();

        orders.add(orderRow(1, 4, "Laptop", 1200));
        orders.add(orderRow(2, 4, "Mouse", 25));
        orders.add(orderRow(3, 1, "Keyboard", 90));
        orders.add(orderRow(4, 8, "Monitor", 300));
        orders.add(orderRow(5, 5, "Desk", 450));
        orders.add(orderRow(6, 12, "Chair", 210));
        orders.add(orderRow(7, 10, "Webcam", 80));
        orders.add(orderRow(8, 18, "Headset", 120));
        orders.add(orderRow(9, 2, "Cable", 15));
        orders.add(orderRow(10, 9, "Speaker", 150));
        orders.add(orderRow(11, 15, "Router", 95));
        orders.add(orderRow(12, 13, "Card", 400));

        Map<String, Object> giftCard = new HashMap<>();
        giftCard.put("id", 13);
        giftCard.put("user_id", null);
        giftCard.put("product", "Gift card");
        giftCard.put("total", 60);
        orders.add(giftCard);

        DATA.put("orders", orders);

        Map<String, Class<?>> orderSchema = new HashMap<>();
        orderSchema.put("id", Integer.class);
        orderSchema.put("user_id", Integer.class);
        orderSchema.put("product", String.class);
        orderSchema.put("total", Integer.class);

        SCHEMAS.put("orders", orderSchema);

        /*
         * inventory — shares 'product' with orders but not
         * with users, and carries order_id as its join key,
         * so a double join can demonstrate ambiguous bare
         * field resolution.
         */
        List<Map<String, Object>> inventory = new ArrayList<>();

        inventory.add(inventoryRow(1, 1, "Laptop", 40));
        inventory.add(inventoryRow(2, 2, "Mouse", 300));
        inventory.add(inventoryRow(3, 4, "Monitor", 25));
        inventory.add(inventoryRow(4, 5, "Desk", 12));
        inventory.add(inventoryRow(5, 8, "Chair", 30));

        DATA.put("inventory", inventory);

        Map<String, Class<?>> inventorySchema = new HashMap<>();
        inventorySchema.put("id", Integer.class);
        inventorySchema.put("order_id", Integer.class);
        inventorySchema.put("product", String.class);
        inventorySchema.put("stock", Integer.class);

        SCHEMAS.put("inventory", inventorySchema);
    }

    private static Map<String, Object> orderRow(
            int id,
            Integer userId,
            String product,
            int total
    ) {

        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("user_id", userId);
        row.put("product", product);
        row.put("total", total);

        return row;
    }

    private static Map<String, Object> inventoryRow(
            int id,
            int orderId,
            String product,
            int stock
    ) {

        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("order_id", orderId);
        row.put("product", product);
        row.put("stock", stock);

        return row;
    }

    private MockData() {
    }
}