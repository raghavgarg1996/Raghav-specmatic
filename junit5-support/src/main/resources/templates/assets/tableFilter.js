/* DOM Elements */
const resultsList = document.querySelector("ol#results");
const overlay = document.getElementById("overlay");
const allTests = document.querySelector("#all");

/* VARIABLES */
const tableRows = Array.from(document.querySelectorAll("table#reports tbody tr"));
const groupedRows = groupRowsByPathAndMethod(tableRows);

/* Event Listeners */
resultsList.addEventListener("click", (event) => {
  const target = event.target;
  let nearestListItem = target.closest("li");

  if (nearestListItem?.getAttribute("data-type")) {
    if (reportTable.getAttribute("data-filter") === nearestListItem.getAttribute("data-type")) {
      nearestListItem = allTests;
    }

    const resultType = nearestListItem.getAttribute("data-type");
    updateTable(groupedRows[resultType]);
    reportTable.setAttribute("data-filter", resultType);
    goBackToTable(false);
    animateOverlay(nearestListItem);
    event.stopPropagation();
  }
});

/* INIT */
animateOverlay(allTests);

/* Utils */
function animateOverlay(summaryLiElem, offset = 1.25) {
  const index = summaryLiElem.getAttribute("data-index");
  const translateX = `calc(${index}00% + ${offset * index}rem)`;
  overlay.style.transform = `translateX(${translateX})`;
}

function groupRowsByPathAndMethod(tableRows) {
  const categories = { Success: {}, Error: {}, Failed: {}, Skipped: {}, All: {} };

  for (const row of tableRows) {
    const rowValues = extractValuesFromTableRow(row);
    const { path, method, color, response, type } = rowValues;

    if (!(type in categories)) {
      throw new Error(`Unknown type: ${row.type}`);
    }

    if (!categories[type][path]) categories[type][path] = {};
    if (!categories[type][path][method]) categories[type][path][method] = {};
    if (!categories[type][path][method][response]) categories[type][path][method][response] = [];
    categories[type][path][method][response].push(rowValues);

    if (!categories.All[path]) categories.All[path] = {};
    if (!categories.All[path][method]) categories.All[path][method] = {};
    if (!categories.All[path][method][response]) categories.All[path][method][response] = [];
    categories.All[path][method][response].push(rowValues);
  }

  return categories;
}

function updateTable(groupedRows) {
  for (const row of tableRows) {
    const children = Array.from(row.children);
    const [coverageTd, pathTd, methodTd, ...rest] = children;
    const { path, method, response } = extractValuesFromTableRow(row);

    const pathExists = path in groupedRows;
    const methodExists = pathExists && method in groupedRows[path];

    row.classList.toggle("hidden", !pathExists);

    if (methodTd.getAttribute("data-main") === "true") {
      methodTd.classList.toggle("hidden", !methodExists);
    }

    for (const row of rest) {
      const responseExists = pathExists && methodExists && response in groupedRows[path][method];
      row.classList.toggle("hidden", !responseExists);
    }
  }
}
