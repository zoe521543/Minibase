package ed.inf.adbs.minibase.operator;

import ed.inf.adbs.minibase.base.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Apply JOIN operation on the output tuple sets of two child operators.
 * The implicit inner join conditions (i.e. the two join relations contain same variables)
 *      will be processed automatically in this class.
 * Other explicit join conditions (e.g. some comparison between two different variables in the two join relations separately)
 *      are provided by {@link ComparisonAtom} in query body, which is an input parameter in the constructor.
 * All these join conditions (either implicit or explicit) will be converted into {@link JoinCondition} instances,
 *      which use {@link JoinCondition#check(Tuple, Tuple)} to check whether a combination of left and right tuple satisfies the requirement.
 */
public class JoinOperator extends Operator {

    private Operator leftChild;
    private Operator rightChild;

    private List<JoinCondition> conditions = new ArrayList<>();

    private HashMap<Integer, Integer> joinConditionIndices = new HashMap<>();
    // a map from variable index in left tuples to index in right tuples that represents the same variable
    // this is used for inner join checks, duplication columns will be removed by referring to this map.

    private List<Integer> rightDuplicateColumns = new ArrayList<>();
    // the columns in right child to be removed (due to inner join / duplicates with columns in left child)

    private Tuple leftTuple = null;
    // the current being checked output tuple of left child

    /**
     * Initialise the operator:
     *      (1) Convert the input {@link ComparisonAtom} list into {@link JoinCondition} list,
     *          which implements specific methods for checking whether a tuple satisfy a certain condition.
     *      (2) Check the inner join conditions, i.e. find the variables which appear in both left and right tuples,
     *          interpret these conditions as {@link SelectOperator} instances.
     *      (3) Update the variable mask, the right child tuples will be concatenated to
     *          the left child tuples (with the inner join duplicated columns be removed)
     * @param leftChild left child operator.
     * @param rightChild right child operator.
     * @param comparisonAtoms the explicit join conditions provided by {@link ComparisonAtom} in query body.
     */
    public JoinOperator(Operator leftChild, Operator rightChild, List<ComparisonAtom> comparisonAtoms) {
        this.leftChild = leftChild;
        List<String> leftVariableMask = leftChild.getVariableMask();
        this.rightChild = rightChild;
        List<String> rightVariableMask = rightChild.getVariableMask();

        for (ComparisonAtom compAtom : comparisonAtoms)
            this.conditions.add(new JoinCondition(compAtom, leftVariableMask, rightVariableMask));

        // Find if the right relation contains some variables that also appear in left relation.
        // These identical variable pairs indicate some inner join conditions.
        for (String leftVar : leftVariableMask) {
            this.variableMask.add(leftVar);
            if (rightVariableMask.contains(leftVar)) {
                // construct new join conditions for these identical variable pairs
                this.joinConditionIndices.put(leftVariableMask.indexOf(leftVar), rightVariableMask.indexOf(leftVar));
                this.rightDuplicateColumns.add(rightVariableMask.indexOf(leftVar));
            }
        }
        for (String rightVar : rightVariableMask) {
            if (rightVar == null) {
                this.variableMask.add(null);
            } else {
                if (!this.variableMask.contains(rightVar))
                    this.variableMask.add(rightVar);
            }
        }
    }

    /**
     * Reset the states of both child operators.
     */
    @Override
    public void reset() {
        this.leftChild.reset();
        this.rightChild.reset();
        this.leftTuple = null;
    }

    /**
     * Get the next joined tuple from output of left and right child operators.
     * Implements an outer loop on left child tuples (and use {@code this.leftTuple} to track the being checked left tuple).
     * Implements an inner loop on right child tuples.
     * @return the next joined tuple that satisfies the join conditions.
     */
    @Override
    public Tuple getNextTuple() {
        // the leftTuple is stored in the instance, otherwise each left tuple can only be joined with at most one right tuple
        if (this.leftTuple == null)
            this.leftTuple = this.leftChild.getNextTuple();

        while (this.leftTuple != null) {
            // Fix a tuple in outer loop, then iterate over the tuples in inner loop
            Tuple rightTuple = this.rightChild.getNextTuple();
            while (rightTuple != null) {

                boolean pass = true;
                // check the inner join conditions provided by same variable names in two query atoms
                for (Integer leftIndex : this.joinConditionIndices.keySet()) {
                    int rightIndex = this.joinConditionIndices.get(leftIndex);
                    if (!this.leftTuple.getTerms().get(leftIndex).equals(rightTuple.getTerms().get(rightIndex))) {
                        pass = false;
                        break;
                    }
                }
                // check the join conditions provided by extra ComparisonAtom, and involves different variables
                if (pass) {
                    for (JoinCondition condition : this.conditions) {
                        if (!condition.check(this.leftTuple, rightTuple)) {
                            pass = false;
                            break;
                        }
                    }
                }

                // if all conditions are satisfied, construct a new Tuple instance as join result
                if (pass) {
                    List<Term> joinTermList = new ArrayList<>();
                    // the join result contains all columns in left tuple, and the non-duplicate columns in right tuple
                    for (Term leftTerm : this.leftTuple.getTerms())
                        joinTermList.add(leftTerm);
                    for (int i = 0; i < rightTuple.getTerms().size(); i++) {
                        if (!this.rightDuplicateColumns.contains(i)) {
                            joinTermList.add(rightTuple.getTerms().get(i));
                        }
                    }
                    return new Tuple("Join", joinTermList);
                }

                // otherwise, check the next right tuple
                rightTuple = this.rightChild.getNextTuple();
            }
            // reset the right child operator, so the inner loop will be restarted from beginning
            this.rightChild.reset();
            // move to the next outer loop tuple
            this.leftTuple = this.leftChild.getNextTuple();
        }
        return null;
    }

    /**
     * Unit test of JoinOperator, output is printed to the console.
     * @param args Command line inputs, can be empty.
     */
    public static void main(String[] args) {
        DBCatalog dbc = DBCatalog.getInstance();
        dbc.init("data/evaluation/db");

        // Test on query 5:   Q(x, y, z, u, w, t) :- R(x, y, z), S(u, w, t), x = u
        System.out.println("Testing query: Q(x, y, z, u, w, t) :- R(x, y, z), S(u, w, t), x = u");

        // Q(x, y, z, u, w, t)
        List<Term> queryHeadTerms = new ArrayList<>();
        queryHeadTerms.add( new Variable("x"));
        queryHeadTerms.add( new Variable("y"));
        queryHeadTerms.add( new Variable("z"));
        queryHeadTerms.add( new Variable("u"));
        queryHeadTerms.add( new Variable("w"));
        queryHeadTerms.add( new Variable("t"));
        RelationalAtom queryHeadAtom = new RelationalAtom("Q", queryHeadTerms);
        System.out.println(queryHeadAtom);

        // R(x, y, z)
        List<Term> queryAtomTerms1 = new ArrayList<>();
        queryAtomTerms1.add( new Variable("x"));
        queryAtomTerms1.add( new Variable("y"));
        queryAtomTerms1.add( new Variable("z"));
        RelationalAtom queryBodyAtomR = new RelationalAtom("R", queryAtomTerms1);
        System.out.println(queryBodyAtomR);

        // S(u, w, t)
        List<Term> queryAtomTerms2 = new ArrayList<>();
        queryAtomTerms2.add( new Variable("u"));
        queryAtomTerms2.add( new Variable("w"));
        queryAtomTerms2.add( new Variable("t"));
        RelationalAtom queryBodyAtomS = new RelationalAtom("S", queryAtomTerms2);
        System.out.println(queryBodyAtomS);

        // x = u
        List<ComparisonAtom> compAtomList = new ArrayList<>();
        ComparisonAtom compAtom1 = new ComparisonAtom(
                new Variable("x"), new Variable("u"), ComparisonOperator.fromString("="));
        System.out.println(compAtom1);
        compAtomList.add(compAtom1);

        System.out.println("-----------------------------------");

        ScanOperator scanOpR = new ScanOperator(queryBodyAtomR);
        ScanOperator scanOpS = new ScanOperator(queryBodyAtomS);

        JoinOperator joinOp = new JoinOperator(scanOpR, scanOpS, compAtomList);
        joinOp.dump(null);
        joinOp.reset();
        System.out.println("-----------------------------------");

        ProjectOperator projOp = new ProjectOperator(joinOp, queryHeadAtom);
        projOp.dump(null);
    }
}
