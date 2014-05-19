/*
 *    Fernflower - The Analytical Java Decompiler
 *    http://www.reversed-java.com
 *
 *    (C) 2008 - 2010, Stiver
 *
 *    This software is NEITHER public domain NOR free software 
 *    as per GNU License. See license.txt for more details.
 *
 *    This software is distributed WITHOUT ANY WARRANTY; without 
 *    even the implied warranty of MERCHANTABILITY or FITNESS FOR 
 *    A PARTICULAR PURPOSE. 
 */

package de.fernflower.modules.decompiler.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.fernflower.code.CodeConstants;
import de.fernflower.code.InstructionSequence;
import de.fernflower.main.DecompilerContext;
import de.fernflower.main.collectors.CounterContainer;
import de.fernflower.main.extern.IFernflowerLogger;
import de.fernflower.modules.decompiler.StatEdge;
import de.fernflower.modules.decompiler.StrongConnectivityHelper;
import de.fernflower.modules.decompiler.exps.Exprent;
import de.fernflower.util.VBStyleCollection;

public class Statement {
	
	public static final int STATEDGE_ALL = 1 << 31;
	public static final int STATEDGE_DIRECT_ALL = 1 << 30;
	
	public static final int DIRECTION_BACKWARD = 0;
	public static final int DIRECTION_FORWARD = 1;
	
	public static final int TYPE_GENERAL = 0; 
	public static final int TYPE_IF = 2; 
	public static final int TYPE_DO = 5; 
	public static final int TYPE_SWITCH = 6; 
	public static final int TYPE_TRYCATCH = 7; 
	public static final int TYPE_BASICBLOCK = 8; 
	public static final int TYPE_FINALLY = 9; 
	public static final int TYPE_SYNCRONIZED = 10; 
	public static final int TYPE_PLACEHOLDER = 11; 
	public static final int TYPE_CATCHALL = 12; 
	public static final int TYPE_ROOT = 13; 
	public static final int TYPE_DUMMYEXIT = 14; 
	public static final int TYPE_SEQUENCE = 15; 

	
	public static final int LASTBASICTYPE_IF = 0;
	public static final int LASTBASICTYPE_SWITCH = 1;
	public static final int LASTBASICTYPE_GENERAL = 2;
	

	// *****************************************************************************
	// public fields
	// *****************************************************************************
	
	public int type;
	
	public Integer id;

	// *****************************************************************************
	// private fields
	// *****************************************************************************

	private Map<Integer, List<StatEdge>> mapSuccEdges = new HashMap<Integer, List<StatEdge>>();
	private Map<Integer, List<StatEdge>> mapPredEdges = new HashMap<Integer, List<StatEdge>>();
	
	private Map<Integer, List<Statement>> mapSuccStates = new HashMap<Integer, List<Statement>>();
	private Map<Integer, List<Statement>> mapPredStates = new HashMap<Integer, List<Statement>>();

	// statement as graph
	protected VBStyleCollection<Statement, Integer> stats = new VBStyleCollection<Statement, Integer>();
	
	protected Statement parent;
	
	protected Statement first;
	
	protected List<Exprent> exprents;

	protected HashSet<StatEdge> labelEdges = new HashSet<StatEdge>();
	
	protected List<Exprent> varDefinitions = new ArrayList<Exprent>(); 
	
	// copied statement, s. deobfuscating of irreducible CFGs
	private boolean copied = false;

	// relevant for the first stage of processing only
	// set to null after initializing of the statement structure

	protected Statement post;

	protected int lastBasicType = LASTBASICTYPE_GENERAL;
	
	protected boolean isMonitorEnter;

	protected boolean containsMonitorExit;
	
	protected HashSet<Statement> continueSet = new HashSet<Statement>(); 
	
	// *****************************************************************************
	// initializers
	// *****************************************************************************
	
	{
		// set statement id
		id = DecompilerContext.getCountercontainer().getCounterAndIncrement(CounterContainer.STATEMENT_COUNTER);
	}
	
	// *****************************************************************************
	// public methods
	// *****************************************************************************

	public void clearTempInformation() {
		
		post = null;
		continueSet = null; 

		copied = false;
		// FIXME: used in FlattenStatementsHelper.flattenStatement()! check and remove 
		//lastBasicType = LASTBASICTYPE_GENERAL;
		isMonitorEnter = false;;
		containsMonitorExit = false;
		
		for(Map<Integer, List<StatEdge>> map : new Map[]{mapSuccEdges, mapPredEdges}) {
			map.remove(StatEdge.TYPE_EXCEPTION);
			
			List<StatEdge> lst = map.get(STATEDGE_DIRECT_ALL);
			if(lst != null) {
				map.put(STATEDGE_ALL, new ArrayList<StatEdge>(lst));
			} else {
				map.remove(STATEDGE_ALL);
			}
		}

		for(Map<Integer, List<Statement>> map : new Map[]{mapSuccStates, mapPredStates}) {
			map.remove(StatEdge.TYPE_EXCEPTION);

			List<Statement> lst = map.get(STATEDGE_DIRECT_ALL);
			if(lst != null) {
				map.put(STATEDGE_ALL, new ArrayList<Statement>(lst));
			} else {
				map.remove(STATEDGE_ALL);
			}
		}
		
	}
	
	public void collapseNodesToStatement(Statement stat) {
		
		Statement head = stat.getFirst();
		Statement post = stat.getPost();  

		VBStyleCollection<Statement, Integer> setNodes = stat.getStats();

		// post edges
		if(post != null) {
			for(StatEdge edge : post.getEdges(STATEDGE_DIRECT_ALL, DIRECTION_BACKWARD)) {
				if(stat.containsStatementStrict(edge.getSource())) {
					edge.getSource().changeEdgeType(DIRECTION_FORWARD, edge, StatEdge.TYPE_BREAK);
					stat.addLabeledEdge(edge);
				}
			}
		}
		
		// regular head edges
		for(StatEdge prededge : head.getAllPredecessorEdges()) {
			
			if(prededge.getType() != StatEdge.TYPE_EXCEPTION &&
					stat.containsStatementStrict(prededge.getSource())) {
				prededge.getSource().changeEdgeType(DIRECTION_FORWARD, prededge, StatEdge.TYPE_CONTINUE);
				stat.addLabeledEdge(prededge);
			}
			
			head.removePredecessor(prededge);
			prededge.getSource().changeEdgeNode(DIRECTION_FORWARD, prededge, stat);
			stat.addPredecessor(prededge);
		}
		
		if(setNodes.containsKey(first.id)) {
			first = stat;
		}
		
		// exception edges
		Set<Statement> setHandlers = new HashSet<Statement>(head.getNeighbours(StatEdge.TYPE_EXCEPTION, DIRECTION_FORWARD)); 
		for(Statement node : setNodes) {
			setHandlers.retainAll(node.getNeighbours(StatEdge.TYPE_EXCEPTION, DIRECTION_FORWARD));
		}
		
		if(!setHandlers.isEmpty()) {

			for(StatEdge edge : head.getEdges(StatEdge.TYPE_EXCEPTION, DIRECTION_FORWARD)) {
				Statement handler = edge.getDestination();

				if(setHandlers.contains(handler)) {
					if(!setNodes.containsKey(handler.id)) {
						stat.addSuccessor(new StatEdge(stat, handler, edge.getExceptions()));
					}
				}
			}

			for(Statement node : setNodes) {
				for(StatEdge edge : node.getEdges(StatEdge.TYPE_EXCEPTION, DIRECTION_FORWARD)) {
					if(setHandlers.contains(edge.getDestination())) {
						node.removeSuccessor(edge);
					}
				}
			}
		}
		
		if(post!=null && !stat.getNeighbours(StatEdge.TYPE_EXCEPTION, DIRECTION_FORWARD).contains(post)) { // TODO: second condition redundant?
			stat.addSuccessor(new StatEdge(StatEdge.TYPE_REGULAR, stat, post));
		}
		
		
		// adjust statement collection
		for(Statement st: setNodes) {
			stats.removeWithKey(st.id);
		}
		
		stats.addWithKey(stat, stat.id);
		
		stat.setAllParent();
		stat.setParent(this);
		
		stat.buildContinueSet();
		// monitorenter and monitorexit
		stat.buildMonitorFlags();

		if(stat.type == Statement.TYPE_SWITCH) {
			// special case switch, sorting leaf nodes
			((SwitchStatement)stat).sortEdgesAndNodes();
		}
		
	}
	
	public void setAllParent() {
		for(Statement st: stats) {
			st.setParent(this);
		}
	}
	
	public void addLabeledEdge(StatEdge edge) {
		
		if(edge.closure != null) {
			edge.closure.getLabelEdges().remove(edge);
		}
		edge.closure = this;
		this.getLabelEdges().add(edge);
	}
	
	private void addEdgeDirectInternal(int direction, StatEdge edge, int edgetype) {

		Map<Integer, List<StatEdge>> mapEdges = direction==DIRECTION_BACKWARD?mapPredEdges:mapSuccEdges;
		Map<Integer, List<Statement>> mapStates = direction==DIRECTION_BACKWARD?mapPredStates:mapSuccStates;
		
		List<StatEdge> lst = mapEdges.get(edgetype);
		if(lst == null) {
			mapEdges.put(edgetype, lst = new ArrayList<StatEdge>());
		}
		lst.add(edge);
		
		List<Statement> lstStates = mapStates.get(edgetype);
		if(lstStates == null) {
			mapStates.put(edgetype, lstStates = new ArrayList<Statement>());
		}
		lstStates.add(direction==DIRECTION_BACKWARD?edge.getSource():edge.getDestination());
	}
	
	private void addEdgeInternal(int direction, StatEdge edge) {
		
		int type = edge.getType();
		
		int[] arrtypes;
		if(type == StatEdge.TYPE_EXCEPTION) {
			arrtypes = new int[] {STATEDGE_ALL, StatEdge.TYPE_EXCEPTION};
		} else {
			arrtypes = new int[] {STATEDGE_ALL, STATEDGE_DIRECT_ALL, type};
		}
		
		for(int edgetype : arrtypes) {
			addEdgeDirectInternal(direction, edge, edgetype);
		}
		
	}
	
	private void removeEdgeDirectInternal(int direction, StatEdge edge, int edgetype) {
		
		Map<Integer, List<StatEdge>> mapEdges = direction==DIRECTION_BACKWARD?mapPredEdges:mapSuccEdges;
		Map<Integer, List<Statement>> mapStates = direction==DIRECTION_BACKWARD?mapPredStates:mapSuccStates;

		List<StatEdge> lst = mapEdges.get(edgetype);
		if(lst != null) {
			int index = lst.indexOf(edge);
			if(index >= 0) {
				lst.remove(index);
				mapStates.get(edgetype).remove(index);
			}
		}
		
	}
	
	private void removeEdgeInternal(int direction, StatEdge edge) {
		
		int type = edge.getType();
		
		int[] arrtypes;
		if(type == StatEdge.TYPE_EXCEPTION) {
			arrtypes = new int[] {STATEDGE_ALL, StatEdge.TYPE_EXCEPTION};
		} else {
			arrtypes = new int[] {STATEDGE_ALL, STATEDGE_DIRECT_ALL, type};
		}
		
		for(int edgetype : arrtypes) {
			removeEdgeDirectInternal(direction, edge, edgetype);
		}
		
	}
	
	public void addPredecessor(StatEdge edge) {
		addEdgeInternal(DIRECTION_BACKWARD, edge);
	}

	public void removePredecessor(StatEdge edge) {

		if(edge == null) {  // FIXME: redundant?
			return;
		}
		
		removeEdgeInternal(DIRECTION_BACKWARD, edge);
	}
	
	public void addSuccessor(StatEdge edge) {
		addEdgeInternal(DIRECTION_FORWARD, edge);
		
		if(edge.closure != null) {
			edge.closure.getLabelEdges().add(edge);
		}

		edge.getDestination().addPredecessor(edge);
	}

	public void removeSuccessor(StatEdge edge) {

		if(edge == null) {
			return;
		}
		
		removeEdgeInternal(DIRECTION_FORWARD, edge);
		
		if(edge.closure != null) {
			edge.closure.getLabelEdges().remove(edge);
		}
		
		if(edge.getDestination() != null) {  // TODO: redundant?
			edge.getDestination().removePredecessor(edge);
		}
	}
	
	// TODO: make obsolete and remove
	public void removeAllSuccessors(Statement stat) {

		if(stat == null) {
			return;
		}
		
		for(StatEdge edge : getAllSuccessorEdges()) {
			if(edge.getDestination() == stat) {
				removeSuccessor(edge);
			}
		}
	}
	
	public HashSet<Statement> buildContinueSet() {
		continueSet.clear();
		
		for(Statement st: stats) {
			continueSet.addAll(st.buildContinueSet());
			if(st != first) {
				continueSet.remove(st.getBasichead());
			}
		}
		
		for(StatEdge edge: getEdges(StatEdge.TYPE_CONTINUE, DIRECTION_FORWARD)) {
			continueSet.add(edge.getDestination().getBasichead());
		}
		
		if(type == Statement.TYPE_DO) {
			continueSet.remove(first.getBasichead()); 
		}
		
		return continueSet;
	}
	
	public void buildMonitorFlags() {
		
		for(Statement st: stats) {
			st.buildMonitorFlags();
		}
		
		switch(type) {
		case TYPE_BASICBLOCK:
			BasicBlockStatement bblock = (BasicBlockStatement)this;
			InstructionSequence seq = bblock.getBlock().getSeq();
			
			if(seq!=null && seq.length()>0) {
				for(int i=0;i<seq.length();i++) {
					if(seq.getInstr(i).opcode == CodeConstants.opc_monitorexit) {
						containsMonitorExit = true;
						break;
					}
				}
				isMonitorEnter = (seq.getLastInstr().opcode == CodeConstants.opc_monitorenter);   
			}
			break;
		case TYPE_SEQUENCE:
		case TYPE_IF:
			containsMonitorExit = false;
			for(Statement st: stats) {
				containsMonitorExit |= st.isContainsMonitorExit();
			}
			
			break;
		case TYPE_SYNCRONIZED:
		case TYPE_ROOT:
		case TYPE_GENERAL:
			break;
		default:
			containsMonitorExit = false;
			for(Statement st: stats) {
				containsMonitorExit |= st.isContainsMonitorExit();
			}
		}
	}
	
	
	public List<Statement> getReversePostOrderList() {
		return getReversePostOrderList(first);
	}

	public List<Statement> getReversePostOrderList(Statement stat) {
		List<Statement> res = new ArrayList<Statement>();
		
		addToReversePostOrderListIterative(stat, res);
		
		return res;
	}
		
	public List<Statement> getPostReversePostOrderList() {
		return getPostReversePostOrderList(null);
	}
	
	public List<Statement> getPostReversePostOrderList(List<Statement> lstexits) {
		
		List<Statement> res = new ArrayList<Statement>();
		
		if(lstexits == null) {
			StrongConnectivityHelper schelper = new  StrongConnectivityHelper(this);
			lstexits = StrongConnectivityHelper.getExitReps(schelper.getComponents());
		}
		
		HashSet<Statement> setVisited = new HashSet<Statement>();
		
		for(Statement exit : lstexits) {
			addToPostReversePostOrderList(exit, res, setVisited);
		}
		
		if(res.size() != stats.size()) {
			DecompilerContext.getLogger().writeMessage("computing post reverse post order failed!", IFernflowerLogger.ERROR);

			throw new RuntimeException("parsing failure!");
		}
		
		return res;
	}

	public boolean containsStatement(Statement stat) {
		return this == stat || containsStatementStrict(stat);
	}
	
	public boolean containsStatementStrict(Statement stat) {
		
		if(stats.contains(stat)) {
			return true;
		}
		
		for(int i=0;i<stats.size();i++) {
			if(stats.get(i).containsStatementStrict(stat)) {
				return true;
			}
		}
		
		return false;
	}
	
	// to be overwritten
	public String toJava() {
		return toJava(0);
	}
	
	public String toJava(int indent) {
		throw new RuntimeException("not implemented");
	}
	
	// TODO: make obsolete and remove
	public List<Object> getSequentialObjects() {
		return new ArrayList<Object>(stats);
	}

	public void initExprents() {
		; // do nothing
	}
	
	public void replaceExprent(Exprent oldexpr, Exprent newexpr) {
		; // do nothing
	}
	
	public Statement getSimpleCopy() {
		throw new RuntimeException("not implemented");
	}
	
	public void initSimpleCopy() {
		if(!stats.isEmpty()) {
			first = stats.get(0);
		}
	}
	
	public void replaceStatement(Statement oldstat, Statement newstat) {
		
		for(StatEdge edge : oldstat.getAllPredecessorEdges()) {
			oldstat.removePredecessor(edge);
			edge.getSource().changeEdgeNode(DIRECTION_FORWARD, edge, newstat);
			newstat.addPredecessor(edge);
		}
		
		for(StatEdge edge : oldstat.getAllSuccessorEdges()) {
			oldstat.removeSuccessor(edge);
			edge.setSource(newstat);
			newstat.addSuccessor(edge);
		}
		
		int statindex = stats.getIndexByKey(oldstat.id);
		stats.removeWithKey(oldstat.id);
		stats.addWithKeyAndIndex(statindex, newstat, newstat.id);
		
		newstat.setParent(this);
		newstat.post = oldstat.post;
		
		if(first == oldstat) {
			first = newstat;
		}

		List<StatEdge> lst = new ArrayList<StatEdge>(oldstat.getLabelEdges()); 
		
		for(int i=lst.size()-1;i>=0;i--) {
			StatEdge edge = lst.get(i);
			if(edge.getSource() != newstat) {
				newstat.addLabeledEdge(edge);
			} else {
				if(this == edge.getDestination() || this.containsStatementStrict(edge.getDestination())) {
					edge.closure = null;
				} else {
					this.addLabeledEdge(edge);
				}
			}
		}
		
		oldstat.getLabelEdges().clear();
	}

	
	// *****************************************************************************
	// private methods
	// *****************************************************************************
	
	private void addToReversePostOrderListIterative(Statement root, List<Statement> lst) {
		
		LinkedList<Statement> stackNode = new LinkedList<Statement>();
		LinkedList<Integer> stackIndex = new LinkedList<Integer>();
		HashSet<Statement> setVisited = new HashSet<Statement>(); 
		
		stackNode.add(root);
		stackIndex.add(0);
		
		while(!stackNode.isEmpty()) {
			
			Statement node = stackNode.getLast();
			int index = stackIndex.removeLast();

			setVisited.add(node);
			
			List<StatEdge> lstEdges = node.getAllSuccessorEdges();
			
			for(;index<lstEdges.size();index++) {
				StatEdge edge = lstEdges.get(index);
				Statement succ = edge.getDestination();
				
				if(!setVisited.contains(succ) && 
						(edge.getType() == StatEdge.TYPE_REGULAR || edge.getType() == StatEdge.TYPE_EXCEPTION)) { // TODO: edge filter?
					
					stackIndex.add(index+1);
					
					stackNode.add(succ);
					stackIndex.add(0);
					
					break;
				}
			}
			
			if(index == lstEdges.size()) {
				lst.add(0, node);
				
				stackNode.removeLast();
			}
		}
		
	}
	

	private void addToPostReversePostOrderList(Statement stat, List<Statement> lst, HashSet<Statement> setVisited) {
		
		if(setVisited.contains(stat)) { // because of not considered exception edges, s. isExitComponent. Should be rewritten, if possible. 
			return;
		}
		setVisited.add(stat);
		
		for(StatEdge prededge : stat.getEdges(StatEdge.TYPE_REGULAR | StatEdge.TYPE_EXCEPTION, DIRECTION_BACKWARD)) {
			Statement pred = prededge.getSource();
			if(!setVisited.contains(pred)) {
				addToPostReversePostOrderList(pred, lst, setVisited);
			}
		}

		lst.add(0, stat);
	}
	
	// *****************************************************************************
	// getter and setter methods
	// *****************************************************************************

	public void changeEdgeNode(int direction, StatEdge edge, Statement value) {

		Map<Integer, List<StatEdge>> mapEdges = direction==DIRECTION_BACKWARD?mapPredEdges:mapSuccEdges;
		Map<Integer, List<Statement>> mapStates = direction==DIRECTION_BACKWARD?mapPredStates:mapSuccStates;
		
		int type = edge.getType();
		
		int[] arrtypes;
		if(type == StatEdge.TYPE_EXCEPTION) {
			arrtypes = new int[] {STATEDGE_ALL, StatEdge.TYPE_EXCEPTION};
		} else {
			arrtypes = new int[] {STATEDGE_ALL, STATEDGE_DIRECT_ALL, type};
		}

		for(int edgetype : arrtypes) {
			List<StatEdge> lst = mapEdges.get(edgetype);
			if(lst != null) {
				int index = lst.indexOf(edge);
				if(index >= 0) {
					mapStates.get(edgetype).set(index, value);
				}
			}
		}
		
		if(direction == DIRECTION_BACKWARD) {
			edge.setSource(value);
		} else {
			edge.setDestination(value);
		}
		
	}
	
	public void changeEdgeType(int direction, StatEdge edge, int newtype) {
	
		int oldtype = edge.getType(); 
		if(oldtype == newtype) {
			return;
		}
		
		if(oldtype == StatEdge.TYPE_EXCEPTION || newtype == StatEdge.TYPE_EXCEPTION) {
			throw new RuntimeException("Invalid edge type!");
		}

		removeEdgeDirectInternal(direction, edge, oldtype);
		addEdgeDirectInternal(direction, edge, newtype);
		
		if(direction == DIRECTION_FORWARD) {
			edge.getDestination().changeEdgeType(DIRECTION_BACKWARD, edge, newtype);
		}
		
		edge.setType(newtype);
	}
	
	
	private List<StatEdge> getEdges(int type, int direction) {
		
		Map<Integer, List<StatEdge>> map = direction==DIRECTION_BACKWARD?mapPredEdges:mapSuccEdges;
		
		List<StatEdge> res;
		if((type & (type -1)) == 0) {
			res = map.get(type);
			res = res==null?new ArrayList<StatEdge>():new ArrayList<StatEdge>(res); 
		} else {
			res = new ArrayList<StatEdge>();
			for(int edgetype : StatEdge.TYPES) {
				if((type & edgetype) != 0) {
					List<StatEdge> lst = map.get(edgetype);
					if(lst != null) {
						res.addAll(lst);
					}
				}
			}
		}
		
		return res;
	}
	
	public List<Statement> getNeighbours(int type, int direction) {
		
		Map<Integer, List<Statement>> map = direction==DIRECTION_BACKWARD?mapPredStates:mapSuccStates;
		
		List<Statement> res;
		if((type & (type -1)) == 0) {
			res = map.get(type);
			res = res==null?new ArrayList<Statement>():new ArrayList<Statement>(res); 
		} else {
			res = new ArrayList<Statement>();
			for(int edgetype : StatEdge.TYPES) {
				if((type & edgetype) != 0) {
					List<Statement> lst = map.get(edgetype);
					if(lst != null) {
						res.addAll(lst);
					}
				}
			}
		}
		
		return res;
	}

	public Set<Statement> getNeighboursSet(int type, int direction) {
		return new HashSet<Statement>(getNeighbours(type, direction));
	}
	
	public List<StatEdge> getSuccessorEdges(int type) {
		return getEdges(type, DIRECTION_FORWARD);
	}
	
	public List<StatEdge> getPredecessorEdges(int type) {
		return getEdges(type, DIRECTION_BACKWARD);
	}

	public List<StatEdge> getAllSuccessorEdges() {
		return getEdges(STATEDGE_ALL, DIRECTION_FORWARD);
	}
	
	public List<StatEdge> getAllPredecessorEdges() {
		return getEdges(STATEDGE_ALL, DIRECTION_BACKWARD);
	}

	public Statement getFirst() {
		return first;
	}

	public void setFirst(Statement first) {
		this.first = first;
	}

	public Statement getPost() {
		return post;
	}

	public void setPost(Statement post) {
		this.post = post;
	}

	public VBStyleCollection<Statement, Integer> getStats() {
		return stats;
	}

	public int getLastBasicType() {
		return lastBasicType;
	}

	public HashSet<Statement> getContinueSet() {
		return continueSet;
	}

	public boolean isContainsMonitorExit() {
		return containsMonitorExit;
	}

	public boolean isMonitorEnter() {
		return isMonitorEnter;
	}

	public BasicBlockStatement getBasichead() {
		if(type == Statement.TYPE_BASICBLOCK) {
			return (BasicBlockStatement)this;
		} else {
			return first.getBasichead();
		}
	}

	public boolean isLabeled() {

		for(StatEdge edge: labelEdges) {
			if(edge.labeled && edge.explicit) {  // FIXME: consistent setting 
				return true;
			}
		}
		return false;
	}
	
	public boolean hasBasicSuccEdge() {
		boolean res = type == Statement.TYPE_BASICBLOCK || (type == Statement.TYPE_IF &&
				((IfStatement)this).iftype == IfStatement.IFTYPE_IF) ||
				(type == Statement.TYPE_DO && ((DoStatement)this).getLooptype() != DoStatement.LOOP_DO);
		
		// FIXME: default switch
		
		return res;
	}
	

	public Statement getParent() {
		return parent;
	}

	public void setParent(Statement parent) {
		this.parent = parent;
	}

	public HashSet<StatEdge> getLabelEdges() {  // FIXME: why HashSet?
		return labelEdges;
	}

	public List<Exprent> getVarDefinitions() {
		return varDefinitions;
	}

	public List<Exprent> getExprents() {
		return exprents;
	}

	public void setExprents(List<Exprent> exprents) {
		this.exprents = exprents;
	}

	public boolean isCopied() {
		return copied;
	}

	public void setCopied(boolean copied) {
		this.copied = copied;
	}
	
	// helper methods
	public String toString() {
		return id.toString();
	}
}