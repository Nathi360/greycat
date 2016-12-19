package org.mwg.structure.tree;

import org.mwg.Callback;
import org.mwg.Graph;
import org.mwg.Node;
import org.mwg.Type;
import org.mwg.base.BaseNode;
import org.mwg.plugin.NodeState;
import org.mwg.struct.EGraph;
import org.mwg.struct.ENode;
import org.mwg.structure.NTree;


/**
 * Created by assaad on 19/12/2016.
 */
public class ETree extends BaseNode implements NTree {
    public static String NAME = "ETree";

    public static String BOUND_MIN = "_min";
    public static String BOUND_MAX = "_max";
    public static String RESOLUTION = "_resolution";
    public static String BUFFER_SIZE = "_buffersize";
    public static int BUFFER_SIZE_DEF = 0;


    private static String EGRAPH = "_egraph";

    private static long _TOTAL = 0;
    private static long _BUFFER = 1;
    private static long _SUBNODES = 2;
    private static long _VALUE = 3;
    private static long _MIN = 4;
    private static long _MAX = 5;
    private static long _PROFILE = 6;

    private static int _REL = 16;


    public ETree(long p_world, long p_time, long p_id, Graph p_graph) {
        super(p_world, p_time, p_id, p_graph);
    }

    @Override
    public void nearestN(double[] keys, int nbElem, Callback<Node[]> callback) {

    }

    @Override
    public void nearestWithinRadius(double[] keys, double radius, Callback<Node[]> callback) {

    }

    @Override
    public void nearestNWithinRadius(double[] keys, int nbElem, double radius, Callback<Node[]> callback) {

    }

    private static int getRelationId(double[] centerKey, double[] keyToInsert) {
        int result = 0;
        for (int i = 0; i < centerKey.length; i++) {
            if (i != 0) {
                result = result << 1;
            }
            if (keyToInsert[i] > centerKey[i]) {
                result += 1;
            }
        }
        return result + _REL;
    }

    private static boolean checkCreateLevels(double[] min, double[] max, double[] resolutions) {
        for (int i = 0; i < min.length; i++) {
            //todo optimize the 2* later
            if ((max[i] - min[i]) > 2 * resolutions[i]) {
                return true;
            }
        }
        return false;
    }

    private static double[] getCenterMinMax(double[] min, double[] max) {
        double[] center = new double[min.length];
        for (int i = 0; i < min.length; i++) {
            center[i] = (max[i] + min[i]) / 2;
        }
        return center;
    }

    private static double[] getCenter(ENode node) {
        double[] min = (double[]) node.getAt(_MIN);
        double[] max = (double[]) node.getAt(_MAX);
        return getCenterMinMax(min, max);
    }

    public static int counter = 0;

    private static ENode createNewNode(final ENode parent, final double[] min, final double[] max, final double[] center, final double[] keyToInsert, final int buffersize) {
        ENode node = parent.graph().newNode();
        double[] minChild = new double[min.length];
        double[] maxChild = new double[max.length];

        for (int i = 0; i < min.length; i++) {
            if (keyToInsert[i] <= center[i]) {
                minChild[i] = min[i];
                maxChild[i] = center[i];

            } else {
                minChild[i] = center[i];
                maxChild[i] = max[i];
            }
        }
        node.setAt(_SUBNODES, Type.INT, 0);
        node.setAt(_MIN, Type.DOUBLE_ARRAY, minChild);
        node.setAt(_MAX, Type.DOUBLE_ARRAY, maxChild);
        node.setAt(_TOTAL, Type.INT, 0);

        parent.setAt(_SUBNODES, Type.INT, (int) parent.getAt(_SUBNODES) + 1);

        if (buffersize != 0) {
            //todo create buffer here
        }
        counter++;
        return node;
    }

    private static void subInsert(final ENode from, final double[] values, final double[] min, final double[] max, final double[] center, final double[] resolution, final int buffersize, final int lev, final ENode root) {
        int index = getRelationId(center, values);

        ENode child;
        if (from.getAt(index) == null) {
            child = createNewNode(from, min, max, center, values, buffersize);
            from.setAt(index, Type.LONG, child.id());
        } else {
            child = from.graph().lookup((long) from.getAt(index));
        }
        double[] childmin = (double[]) child.getAt(_MIN);
        double[] childmax = (double[]) child.getAt(_MAX);
        double[] childcenter = getCenterMinMax(childmin, childmax);
        internalInsert(child, values, childmin, childmax, childcenter, resolution, buffersize, lev + 1, root);
    }

    private static void internalInsert(final ENode node, final double[] keys, final double[] min, final double[] max, final double[] center, final double[] resolution, final int buffersize, final int lev, final ENode root) {
        if ((int) node.getAt(_SUBNODES) != 0) {
            subInsert(node, keys, min, max, center, resolution, buffersize, lev, root);
        } else if (checkCreateLevels(min, max, resolution)) {

            if (node.getAt(_BUFFER) != null) {
                throw new RuntimeException("should not go here");
                //todo bufferize keys here and check if buffer is full etc
//                if (_tempValues.size() < buffersize) {
//                    _tempValues.add(keys);
//                } else {
//                    // check if we can create subchildren
//                    _subchildren = new NDTree[getChildren(min.length)];
//                    for (double[] _tempValue : _tempValues) {
//                        subInsert(this, _tempValue, min, max, center, resolution, maxPerLevel, lev, root);
//                    }
//                    subInsert(this, keys, min, max, center, resolution, maxPerLevel, lev, root);
//                    _tempValues = null;
//                }
            } else {
                subInsert(node, keys, min, max, center, resolution, buffersize, lev, root);
            }
        }
        //Else we reached here last level of the tree, and the array is full, we need to start a profiler
        else {
            //todo add the value later
            double[] profile = (double[]) node.getAt(_PROFILE);
            if (profile == null) {
                profile = new double[keys.length];
                System.arraycopy(keys, 0, profile, 0, keys.length);
            } else {
                for (int i = 0; i < keys.length; i++) {
                    profile[i] += keys[i];
                }
            }
            node.setAt(_PROFILE, Type.DOUBLE_ARRAY, profile);
        }

        //this is for everyone
        node.setAt(_TOTAL, Type.INT, (int) node.getAt(_TOTAL) + 1);
    }

    @Override
    public void insertWith(double[] keys, Node value, Callback<Boolean> callback) {
        NodeState state = unphasedState();

        double[] min = (double[]) state.getFromKey(BOUND_MIN);
        double[] max = (double[]) state.getFromKey(BOUND_MAX);
        double[] resolution = (double[]) state.getFromKey(RESOLUTION);
        EGraph graph = (EGraph) state.getOrCreateFromKey(EGRAPH, Type.EGRAPH);
        int buffersize = state.getFromKeyWithDefault(BUFFER_SIZE, BUFFER_SIZE_DEF);

        ENode root = graph.root();
        if (root == null) {
            root = graph.newNode();
            graph.setRoot(root);
            root.setAt(_TOTAL, Type.INT, 0);
            root.setAt(_SUBNODES, Type.INT, 0);
            root.setAt(_MIN, Type.DOUBLE_ARRAY, min);
            root.setAt(_MAX, Type.DOUBLE_ARRAY, max);
        }
        internalInsert(root, keys, min, max, getCenterMinMax(min, max), resolution, buffersize, 0, root);
    }

    @Override
    public void insert(Node value, Callback<Boolean> callback) {

    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public void setDistance(int distanceType) {

    }

    @Override
    public void setDistanceThreshold(double distanceThreshold) {

    }

    @Override
    public void setFrom(String extractor) {

    }
}