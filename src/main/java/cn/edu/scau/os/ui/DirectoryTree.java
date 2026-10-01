package cn.edu.scau.os.ui;

import cn.edu.scau.os.disk.DiskLayout;
import cn.edu.scau.os.disk.FileSystem;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 目录快照变化时更新模型，按文件路径保留用户的展开和选择状态。 */
final class DirectoryTree extends JTree {
    private List<FileSystem.DirNode> lastNodes;
    private Map<List<String>, TreePath> paths = Map.of();

    DirectoryTree() {
        super(new DefaultMutableTreeNode("加载中…"));
    }

    void update(List<FileSystem.DirNode> nodes) {
        if (nodes.equals(lastNodes)) {
            return;
        }

        List<List<String>> expanded = new ArrayList<>();
        List<List<String>> selected = new ArrayList<>();
        paths.forEach((key, path) -> {
            if (isExpanded(path)) {
                expanded.add(key);
            }
            if (isPathSelected(path)) {
                selected.add(key);
            }
        });

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                "/ (根目录, 盘块 " + DiskLayout.ROOT_BLOCK + ")");
        Map<List<String>, TreePath> newPaths = new LinkedHashMap<>();
        newPaths.put(List.of(), new TreePath(root));
        for (FileSystem.DirNode node : nodes) {
            addNode(root, node, List.of(), newPaths);
        }
        boolean firstUpdate = lastNodes == null;
        setModel(new DefaultTreeModel(root));
        if (firstUpdate) {
            for (int i = 0; i < getRowCount(); i++) {
                expandRow(i);
            }
        } else {
            // 新模型默认展开根节点；先收起，再按旧状态由父到子恢复。
            collapsePath(new TreePath(root));
            for (List<String> key : expanded) {
                TreePath path = newPaths.get(key);
                if (path != null) {
                    expandPath(path);
                }
            }
            // 恢复选择不应重新展开用户已收起的父目录。
            boolean expandsSelection = getExpandsSelectedPaths();
            setExpandsSelectedPaths(false);
            setSelectionPaths(selected.stream().map(newPaths::get)
                    .filter(java.util.Objects::nonNull).toArray(TreePath[]::new));
            setExpandsSelectedPaths(expandsSelection);
        }
        paths = newPaths;
        lastNodes = List.copyOf(nodes);
    }

    private static void addNode(DefaultMutableTreeNode parent, FileSystem.DirNode node,
                                List<String> parentKey, Map<List<String>, TreePath> paths) {
        String label = node.directory()
                ? node.name() + "/  [" + node.attributes() + ", 盘块 " + node.startBlock() + "]"
                : node.name() + "  (" + node.length() + "B, 盘块 " + node.startBlock() + ")";
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(label);
        parent.add(child);
        List<String> key = new ArrayList<>(parentKey);
        key.add(node.name());
        paths.put(List.copyOf(key), new TreePath(child.getPath()));
        for (FileSystem.DirNode descendant : node.children()) {
            addNode(child, descendant, key, paths);
        }
    }
}
