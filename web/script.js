async function loadProducts() {
    const res = await fetch("/viewProducts");
    const products = await res.json();

    const table = document.getElementById("productTable");
    table.innerHTML = "";

    products.forEach(p => {
        table.innerHTML += `
        <tr>
            <td>${p.id}</td>
            <td>${p.name}</td>
            <td>${p.category}</td>
            <td>${p.quantity}</td>
            <td>${p.price}</td>
            <td>${p.expiry_date}</td>
            <td>${p.supplier}</td>
            <td>
                <button class="delete-btn" onclick="deleteProduct(${p.id})">🗑 Delete</button>
            </td>
        </tr>
        `;
    });
}


async function deleteProduct(id) {
    if (!confirm("Are you sure you want to delete this product?")) return;

    const res = await fetch("/deleteProduct?id=" + id, {
        method: "POST"  // ✔ backend expects POST
    });

    const data = await res.json();

    if (data.success) {
        alert("Product deleted successfully!");
        loadProducts();   // ✔ REFRESH TABLE
    } else {
        alert(data.message || "Failed to delete product");
    }
}

