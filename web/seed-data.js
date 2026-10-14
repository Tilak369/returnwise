// Demo catalogue for a fictional fashion retailer, "Lumen & Loom".
// Orders store `deliveredDaysAgo` so the return-window scenarios behave the same
// on every day of the judging period.

export const products = [
  {
    sku: "LL-JN-SLIM-IND", name: "Slim Fit Jeans", category: "JEANS", color: "Indigo", price: 59.9,
    description: "Stretch cotton denim, slim through hip and thigh, tapered leg.",
    sizes: ["28", "30", "32", "33", "34", "36"],
    stockBySize: { "28": 20, "30": 25, "32": 0, "33": 40, "34": 30, "36": 15 },
    fitNote: "Customers report this style runs about one size small in the waist.",
  },
  {
    sku: "LL-JN-REG-IND", name: "Regular Fit Jeans", category: "JEANS", color: "Indigo", price: 59.9,
    description: "Rigid cotton denim, straight regular fit.",
    sizes: ["30", "32", "34", "36"],
    stockBySize: { "30": 25, "32": 30, "34": 25, "36": 10 },
    fitNote: "True to size.",
  },
  {
    sku: "LL-CT-WOOL-CAM", name: "Wool-Blend Overcoat", category: "OUTERWEAR", color: "Camel", price: 249.0,
    description: "Double-faced wool blend, fully lined, horn-effect buttons.",
    sizes: ["S", "M", "L", "XL"],
    stockBySize: { S: 15, M: 20, L: 12, XL: 6 },
    fitNote: "True to size; relaxed shoulder.",
  },
  {
    sku: "LL-DR-WRAP-EMR", name: "Satin Wrap Dress", category: "DRESSES", color: "Emerald", price: 189.0,
    description: "Occasion-wear satin wrap dress with tie waist.",
    sizes: ["XS", "S", "M", "L"],
    stockBySize: { XS: 10, S: 12, M: 6, L: 10 },
    fitNote: "True to size.",
  },
  {
    sku: "LL-SN-RUN-WHT", name: "Court Sneakers", category: "FOOTWEAR", color: "White", price: 89.0,
    description: "Leather-look court sneakers with cushioned insole.",
    sizes: ["40", "41", "42", "43", "44"],
    stockBySize: { "40": 15, "41": 20, "42": 25, "43": 10, "44": 6 },
    fitNote: "Runs half a size large.",
  },
  {
    sku: "LL-SH-LIN-SKY", name: "Linen Shirt", category: "SHIRTS", color: "Sky Blue", price: 45.0,
    description: "Pure linen, relaxed fit, button-down collar.",
    sizes: ["S", "M", "L", "XL"],
    stockBySize: { S: 30, M: 45, L: 35, XL: 20 },
    fitNote: "Relaxed fit; size down for a closer fit.",
  },
];

export const customers = [
  {
    customerId: "C-1001", name: "Priya Raman", tier: "PLUS", ordersLast12Months: 18, returnsLast12Months: 2,
    returnsLast90Days: 0, lifetimeValue: 1420.5,
    notes: ["Loyal member since 2021", "Returns are rare and usually size related"],
  },
  {
    customerId: "C-1002", name: "Daniel Tan", tier: "MEMBER", ordersLast12Months: 6, returnsLast12Months: 1,
    returnsLast90Days: 0, lifetimeValue: 610.0, notes: [],
  },
  {
    customerId: "C-2001", name: "Alex Morgan", tier: "MEMBER", ordersLast12Months: 14, returnsLast12Months: 11,
    returnsLast90Days: 7, lifetimeValue: 2210.0,
    notes: ["High return rate on occasion wear", "Several returns of worn items flagged by store staff"],
  },
];

const order = (orderId, customerId, customerName, deliveredDaysAgo, sku, name, size, price) => ({
  orderId, customerId, customerName, deliveredDaysAgo, channel: "ONLINE",
  items: [{ sku, name, size, quantity: 1, price }],
});

export const orders = [
  order("LL-10021", "C-1001", "Priya Raman", 6, "LL-JN-SLIM-IND", "Slim Fit Jeans", "32", 59.9),
  order("LL-10022", "C-1002", "Daniel Tan", 10, "LL-CT-WOOL-CAM", "Wool-Blend Overcoat", "M", 249.0),
  order("LL-10023", "C-2001", "Alex Morgan", 26, "LL-DR-WRAP-EMR", "Satin Wrap Dress", "S", 189.0),
  order("LL-10024", "C-1002", "Daniel Tan", 41, "LL-SN-RUN-WHT", "Court Sneakers", "42", 89.0),
  order("LL-10025", "C-1001", "Priya Raman", 3, "LL-SH-LIN-SKY", "Linen Shirt", "M", 45.0),
];
