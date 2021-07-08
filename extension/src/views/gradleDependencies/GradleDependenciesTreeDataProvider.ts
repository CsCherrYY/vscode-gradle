import * as vscode from 'vscode';
import * as path from 'path';
import { GradleClient } from '../../client';
import { Icons } from '../../icons';
import { DependencyNode } from '../../proto/gradle_pb';
import { RootProjectsStore } from '../../stores';
import { getGradleDependencies } from '../../tasks/taskUtil';

export class GradleDependenciesTreeDataProvider implements vscode.TreeDataProvider<DependencyNode> {

  constructor (
    private readonly rootProjectStore: RootProjectsStore,
    private readonly client: GradleClient,
    private readonly icons: Icons,
  ) {}

  public async getTreeItem(element: DependencyNode): Promise<vscode.TreeItem> {
    const item = new vscode.TreeItem(element.getName());
    if (element.getLevel() === 0) {
      item.iconPath = vscode.ThemeIcon.File;
      item.resourceUri = vscode.Uri.file(path.join((await this.rootProjectStore.getProjectRoots())[0].getProjectUri().fsPath, "settings.gradle"));
    } else if (element.getLevel() === 1) {
      item.iconPath = new vscode.ThemeIcon("file-submodule");
    } else {
      item.iconPath = new vscode.ThemeIcon("library");
    }
    item.contextValue = "folder";
    item.collapsibleState = (element.getChildrenList() && element.getChildrenList().length) ? vscode.TreeItemCollapsibleState.Collapsed : vscode.TreeItemCollapsibleState.None;
    return item;
  }

  public async getChildren(element?: DependencyNode): Promise<DependencyNode[] | undefined> {
    if (!element) {
      const projectRoots = await this.rootProjectStore.getProjectRoots();
      const reply = await getGradleDependencies(this.client, projectRoots[0]);
      if (!reply) {
        return undefined;
      }
      return reply.getNodeList();
    }
    return element.getChildrenList();
  }
}
