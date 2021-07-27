import * as vscode from "vscode";
import { DependencyNode } from "../../proto/gradle_pb";

export class DependencyItem extends vscode.TreeItem {

  private children: vscode.TreeItem[] | undefined;
  constructor(
    name: string,
    collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly parentTreeItem: vscode.TreeItem,
  ) {
    super(name, collapsibleState);
  }

  public setChildren(children: vscode.TreeItem[]) {
    this.children = children;
  }

  public getChildren(): vscode.TreeItem[] | undefined {
    return this.children;
  }
}

export function convertDependencyNodeToDependencyItem(node: DependencyNode, parent: vscode.TreeItem, dependencyItemMap: Map<string, DependencyItem>): DependencyItem | undefined {
  const children = node.getChildrenList();
  const type = node.getType();
  if ((!children || children.length === 0) && type === "configuration") {
    return undefined;
  }
  let label;
  if (type === "dependency") {
    label = `${node.getGroup()}:${node.getId()}:${node.getVersion()}`;
  } else {
    label = node.getName();
  }

  let dependencyItem: DependencyItem;
  if (dependencyItemMap.has(label) && children?.length > 0) {
    dependencyItem = new DependencyItem(label + " (*)", vscode.TreeItemCollapsibleState.None, parent);
    dependencyItem.command = {
      command: 'gradle.TaskTreeView.reveal',
      title: 'Reveal given item',
      arguments: [
        dependencyItemMap.get(label)
      ]
    };
    dependencyItem.iconPath = (type === "dependency") ? new vscode.ThemeIcon("library") : new vscode.ThemeIcon("file-submodule");
  } else {
    dependencyItem = new DependencyItem(label, children?.length > 0 ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None, parent);
    const childItems = [];
    if (children) {
      for (const child of children) {
        const childItem = convertDependencyNodeToDependencyItem(child, dependencyItem, dependencyItemMap);
        if (childItem) {
          childItems.push(childItem);
        }
      }
      childItems.sort((a, b) => {
        return a.label!.localeCompare(b.label!);
      })
      dependencyItem.setChildren(childItems);
    }
    dependencyItem.iconPath = (type === "dependency") ? new vscode.ThemeIcon("library") : new vscode.ThemeIcon("file-submodule");
    if (type === "dependency" && !dependencyItemMap.has(label)) {
      dependencyItemMap.set(label, dependencyItem);
    }
  }
  return dependencyItem;
}
