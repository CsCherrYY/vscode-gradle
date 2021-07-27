import * as vscode from "vscode";
import { DependencyNode } from "../../proto/gradle_pb";
import { convertDependencyNodeToDependencyItem, DependencyItem } from "./DependencyItem";

export class SubFolderItem extends vscode.TreeItem {
  private children: vscode.TreeItem[] | undefined;
  constructor(
    name: string,
    collapsibleState: vscode.TreeItemCollapsibleState,
    public readonly parentTreeItem: vscode.TreeItem,
    iconPath: vscode.ThemeIcon = new vscode.ThemeIcon("server-process"),
  ) {
    super(name, collapsibleState);
    this.iconPath = iconPath;
  }

  public setChildren(children: vscode.TreeItem[]) {
    this.children = children;
  }

  public getChildren(): vscode.TreeItem[] | undefined {
    return this.children;
  }
}

export function convertDependencyNodeToSubFolderItem(node: DependencyNode, parent: vscode.TreeItem): SubFolderItem | undefined {
  const children = node.getChildrenList();
  // type === "project"
  if (!children || children.length === 0) {
    return undefined;
  }
  const subFolderItem: SubFolderItem = new SubFolderItem("Dependencies", vscode.TreeItemCollapsibleState.Collapsed, parent, new vscode.ThemeIcon("file-submodule"));
  const childNodes = [];
  const dependencyItemMap: Map<string, DependencyItem> = new Map();
  for (const child of children) {
    dependencyItemMap.clear();
    if (child.getName() === "default") {
      continue;
    }
    const childItem = convertDependencyNodeToDependencyItem(child, subFolderItem, dependencyItemMap);
    if (childItem) {
      childNodes.push(childItem);
    }
  }
  childNodes.sort((a, b) => {
    return a.label!.localeCompare(b.label!);
  })
  subFolderItem.setChildren(childNodes);
  return subFolderItem;
}
