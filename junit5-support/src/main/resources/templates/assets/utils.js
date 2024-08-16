const extractValuesFromTableRow = (tableRow) => {
  const values = [...tableRow.children].map((child) => child.textContent);
  return {
    coverage: Number(values[0].trim().slice(0, -1)),
    path: values[1].trim(),
    method: values[2].trim(),
    response: Number(values[3]),
    exercised: Number(values[4]),
    result: values[5].trim(),
    color: tableRow.lastElementChild.getAttribute("data-color"),
  };
};