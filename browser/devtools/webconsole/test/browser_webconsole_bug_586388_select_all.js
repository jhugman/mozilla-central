/* vim:set ts=2 sw=2 sts=2 et: */
/* ***** BEGIN LICENSE BLOCK *****
 * Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/
 *
 * Contributor(s):
 *  Patrick Walton <pcwalton@mozilla.com>
 *
 * ***** END LICENSE BLOCK ***** */

const TEST_URI = "http://example.com/";

function test() {
  let hud;

  addTab(TEST_URI);
  browser.addEventListener("load", function onLoad() {
    browser.removeEventListener("load", onLoad, true);
    openConsole(null, testSelectionWhenMovingBetweenBoxes);
  }, true);

  function testSelectionWhenMovingBetweenBoxes(aHud) {
    hud = aHud;
    let jsterm = hud.jsterm;

    // Fill the console with some output.
    jsterm.clearOutput();
    jsterm.execute("1 + 2");
    jsterm.execute("3 + 4");
    jsterm.execute("5 + 6");

    waitForMessages({
      webconsole: hud,
      messages: [{
        text: "3",
        category: CATEGORY_OUTPUT,
      },
      {
        text: "7",
        category: CATEGORY_OUTPUT,
      },
      {
        text: "11",
        category: CATEGORY_OUTPUT,
      }],
    }).then(performTestsAfterOutput);
  }

  function performTestsAfterOutput() {
    let outputNode = hud.outputNode;

    ok(outputNode.childNodes.length >= 3, "the output node has children after " +
       "executing some JavaScript");

    // Test that the global Firefox "Select All" functionality (e.g. Edit >
    // Select All) works properly in the Web Console.
    let commandController = hud.ui._commandController;
    ok(commandController != null, "the window has a command controller object");

    commandController.selectAll();

    let selectedCount = hud.ui.output.getSelectedMessages().length;
    is(selectedCount, outputNode.childNodes.length,
       "all console messages are selected after performing a regular browser " +
       "select-all operation");

    hud.iframeWindow.getSelection().removeAllRanges();

    // Test the context menu "Select All" (which has a different code path) works
    // properly as well.
    let contextMenuId = outputNode.parentNode.getAttribute("context");
    let contextMenu = hud.ui.document.getElementById(contextMenuId);
    ok(contextMenu != null, "the output node has a context menu");

    let selectAllItem = contextMenu.querySelector("*[command='cmd_selectAll']");
    ok(selectAllItem != null,
       "the context menu on the output node has a \"Select All\" item");

    outputNode.focus();

    selectAllItem.doCommand();

    let selectedCount = hud.ui.output.getSelectedMessages().length;
    is(selectedCount, outputNode.childNodes.length,
       "all console messages are selected after performing a select-all " +
       "operation from the context menu");

    hud.iframeWindow.getSelection().removeAllRanges();

    finishTest();
  }
}
