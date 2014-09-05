package com.tinkerpop.blueprints.pgm.impls.git;

import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Element;
import com.tinkerpop.blueprints.Features;
import com.tinkerpop.blueprints.GraphQuery;
import com.tinkerpop.blueprints.Index;
import com.tinkerpop.blueprints.IndexableGraph;
import com.tinkerpop.blueprints.Parameter;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/**
 * A Blueprints Graph which stores its data in a hierarchy of plain text files which plays well with version control
 * software such as Git.
 * This allows multiple parties to edit a graph concurrently and later to merge their changes.
 * GitGraph also enables partitioning of a graph into functional hierarchies, and to combine graphs in a tree-like
 * fashion.
 * For example, if you have a graph with information about cities, and another with information about people,
 * you can place the two graph directories side by side in a parent directory and load this as a single graph.
 * You can create new vertices and edges, at the parent level, which connect people and cities, and then you can save
 * the whole thing and check it in to Git.
 * Another developer can then check out your work and either load the whole graph or, if he is only interested in
 * either cities or people, one of the subdirectories.  Even if he only loads the graph about cities, he can make
 * local changes to it and push them back to you without invalidating the top-level graph.
 * <p/>
 * User: josh
 * Date: 4/13/11
 * Time: 1:39 PM
 */
public class GitGraph implements IndexableGraph {
    private final File directory;
    private final IndexableGraph base;
    private final GitGraphHelper helper;

    /**
     * Creates a new GitGraph in the specified directory.
     *
     * @param directory the directory to use for data storage.
     *                  If this directory already exists, GitGraph will attempt to load previously saved data.
     *                  Data will be saved to this directory (over-writing any previous contents) when this Graph is shutdown.
     * @throws IOException if the graph cannot be loaded
     */
    public GitGraph(final File directory) throws IOException {
        this(directory, new TinkerGraph());
    }

    /**
     * @param directory the directory for GitGraph storage.
     *                  If this directory already exists, GitGraph will attempt to load previously saved data.
     *                  Data will be saved to this directory when this Graph is shutdown.
     * @param base      a graph for temporary storage.  Note: this graph will be cleared of any pre-existing data.
     * @throws IOException if loading fails
     */
    private GitGraph(final File directory,
                     final IndexableGraph base) throws IOException {
        this.directory = directory;
        this.base = base;

        this.helper = new GitGraphHelper();
        helper.load(directory, base);
    }

    public <T extends Element> Index<T> createIndex(String indexName, Class<T> indexClass, Parameter... indexParameters) {
        return base.createIndex(indexName, indexClass, indexParameters);
    }

    public <T extends Element> Index<T> getIndex(final String indexName,
                                                 final Class<T> indexClass) {
        return base.getIndex(indexName, indexClass);
    }

    public Iterable<Index<? extends Element>> getIndices() {
        return base.getIndices();
    }

    public void dropIndex(final String indexName) {
        base.dropIndex(indexName);
    }

    public Features getFeatures() {
        return base.getFeatures();
    }

    public Vertex addVertex(final Object id) {
        validateElementId(id);
        return base.addVertex(id);
    }

    public Vertex getVertex(final Object id) {
        return base.getVertex(id);
    }

    public void removeVertex(final Vertex vertex) {
        base.removeVertex(vertex);
    }

    public Iterable<Vertex> getVertices() {
        return base.getVertices();
    }

    public Iterable<Vertex> getVertices(String key, Object value) {
        return null;
    }

    public Edge addEdge(final Object id,
                        final Vertex outVertex,
                        final Vertex inVertex,
                        final String label) {
        validateElementId(id);
        validateEdgeVertexId((String) id, (String) outVertex.getId());
        validateEdgeVertexId((String) id, (String) inVertex.getId());
        return base.addEdge(id, outVertex, inVertex, label);
    }

    public Edge getEdge(final Object id) {
        return base.getEdge(id);
    }

    public void removeEdge(final Edge edge) {
        base.removeEdge(edge);
    }

    public Iterable<Edge> getEdges() {
        return base.getEdges();
    }

    public Iterable<Edge> getEdges(String key, Object value) {
        return null;
    }

    public GraphQuery query() {
        return null;
    }

    public void shutdown() {
        try {
            save();
        } catch (IOException e) {
            // TODO: let shutdown() throw an exception?
            e.printStackTrace(System.err);
        }
        base.shutdown();
    }

    private void save() throws IOException {
        helper.save(base, directory);
    }

    private void validateElementId(final Object id) {
        if (null != id && !(id instanceof String)) {
            throw new IllegalArgumentException("element id is not a String: " + id);
        }
    }

    private void validateEdgeVertexId(final String edgeId,
                                      final String vertexId) {
        if (null != edgeId) {
            int i = edgeId.lastIndexOf("/");
            if (i >= 1) {
                if (null == vertexId || !vertexId.startsWith(edgeId.substring(0, i + 1))) {
                    throw null == vertexId
                    ? new IllegalArgumentException("edge '" + edgeId + "' cannot reference vertex with automatically generated id")
                    : new IllegalArgumentException("edge '" + edgeId + "' cannot reference vertex '" + vertexId + "'");
                }
            }
        }
    }

    /*
    public static void main(final String[] args) throws Exception {
        ByteArrayOutputStream helper = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(helper);
        out.writeObject(2);
        out.writeObject(2);
        out.flush();
        out.close();
        for (byte b : helper.toByteArray()) {
            System.out.println("byte: " + b);
        }
        System.exit(0);
    } */
}
