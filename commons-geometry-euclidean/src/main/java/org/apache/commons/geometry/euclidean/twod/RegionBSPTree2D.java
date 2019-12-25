/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.geometry.euclidean.twod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.geometry.core.partitioning.BoundarySource;
import org.apache.commons.geometry.core.partitioning.Hyperplane;
import org.apache.commons.geometry.core.partitioning.Split;
import org.apache.commons.geometry.core.partitioning.bsp.AbstractBSPTree;
import org.apache.commons.geometry.core.partitioning.bsp.AbstractRegionBSPTree;

/** Binary space partitioning (BSP) tree representing a region in two dimensional
 * Euclidean space.
 */
public final class RegionBSPTree2D extends AbstractRegionBSPTree<Vector2D, RegionBSPTree2D.RegionNode2D>
    implements BoundarySource2D {

    /** List of line segment paths comprising the region boundary. */
    private List<Polyline> boundaryPaths;

    /** Create a new, empty region.
     */
    public RegionBSPTree2D() {
        this(false);
    }

    /** Create a new region. If {@code full} is true, then the region will
     * represent the entire 2D space. Otherwise, it will be empty.
     * @param full whether or not the region should contain the entire
     *      2D space or be empty
     */
    public RegionBSPTree2D(boolean full) {
        super(full);
    }

    /** Return a deep copy of this instance.
     * @return a deep copy of this instance.
     * @see #copy(org.apache.commons.geometry.core.partitioning.bsp.BSPTree)
     */
    public RegionBSPTree2D copy() {
        RegionBSPTree2D result = RegionBSPTree2D.empty();
        result.copy(this);

        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Iterable<Segment> boundaries() {
        return createBoundaryIterable(b -> (Segment) b);
    }

    /** {@inheritDoc} */
    @Override
    public Stream<Segment> boundaryStream() {
        return StreamSupport.stream(boundaries().spliterator(), false);
    }

    /** {@inheritDoc} */
    @Override
    public List<Segment> getBoundaries() {
        return createBoundaryList(b -> (Segment) b);
    }

    /** Get the boundary of the region as a list of connected line segment paths. The
     * line segments are oriented such that their minus (left) side lies on the
     * interior of the region.
     * @return line segment paths representing the region boundary
     */
    public List<Polyline> getBoundaryPaths() {
        if (boundaryPaths == null) {
            boundaryPaths = Collections.unmodifiableList(computeBoundaryPaths());
        }
        return boundaryPaths;
    }

    /** Add a convex area to this region. The resulting region will be the
     * union of the convex area and the region represented by this instance.
     * @param area the convex area to add
     */
    public void add(final ConvexArea area) {
        union(from(area));
    }

    /** Return a list of {@link ConvexArea}s representing the same region
     * as this instance. One convex area is returned for each interior leaf
     * node in the tree.
     * @return a list of convex areas representing the same region as this
     *      instance
     */
    public List<ConvexArea> toConvex() {
        final List<ConvexArea> result = new ArrayList<>();

        toConvexRecursive(getRoot(), ConvexArea.full(), result);

        return result;
    }

    /** Recursive method to compute the convex areas of all inside leaf nodes in the subtree rooted at the given
     * node. The computed convex areas are added to the given list.
     * @param node root of the subtree to compute the convex areas for
     * @param nodeArea the convex area for the current node; this will be split by the node's cut hyperplane to
     *      form the convex areas for any child nodes
     * @param result list containing the results of the computation
     */
    private void toConvexRecursive(final RegionNode2D node, final ConvexArea nodeArea, final List<ConvexArea> result) {
        if (node.isLeaf()) {
            // base case; only add to the result list if the node is inside
            if (node.isInside()) {
                result.add(nodeArea);
            }
        } else {
            // recurse
            Split<ConvexArea> split = nodeArea.split(node.getCutHyperplane());

            toConvexRecursive(node.getMinus(), split.getMinus(), result);
            toConvexRecursive(node.getPlus(), split.getPlus(), result);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Split<RegionBSPTree2D> split(final Hyperplane<Vector2D> splitter) {
        return split(splitter, RegionBSPTree2D.empty(), RegionBSPTree2D.empty());
    }

    /** {@inheritDoc} */
    @Override
    public Vector2D project(final Vector2D pt) {
        // use our custom projector so that we can disambiguate points that are
        // actually equidistant from the target point
        final BoundaryProjector2D projector = new BoundaryProjector2D(pt);
        accept(projector);

        return projector.getProjected();
    }

    /** Compute the line segment paths comprising the region boundary.
     * @return the line segment paths comprising the region boundary
     */
    private List<Polyline> computeBoundaryPaths() {
        final InteriorAngleSegmentConnector connector = new InteriorAngleSegmentConnector.Minimize();
        connector.connect(boundaries());

        return connector.connectAll().stream()
                .map(Polyline::simplify).collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override
    protected RegionSizeProperties<Vector2D> computeRegionSizeProperties() {
        // handle simple cases
        if (isFull()) {
            return new RegionSizeProperties<>(Double.POSITIVE_INFINITY, null);
        } else if (isEmpty()) {
            return new RegionSizeProperties<>(0, null);
        }

        // compute the size based on the boundary segments
        double quadrilateralAreaSum = 0.0;

        double scaledSumX = 0.0;
        double scaledSumY = 0.0;

        Vector2D startPoint;
        Vector2D endPoint;
        double signedArea;

        for (Segment segment : boundaries()) {

            if (segment.isInfinite()) {
                // at least on boundary is infinite, meaning that
                // the size is also infinite
                quadrilateralAreaSum = Double.POSITIVE_INFINITY;

                break;
            }

            startPoint = segment.getStartPoint();
            endPoint = segment.getEndPoint();

            // compute the area
            signedArea = startPoint.signedArea(endPoint);

            quadrilateralAreaSum += signedArea;

            // compute scaled coordinate values for the barycenter
            scaledSumX += signedArea * (startPoint.getX() + endPoint.getX());
            scaledSumY += signedArea * (startPoint.getY() + endPoint.getY());
        }

        double size = Double.POSITIVE_INFINITY;
        Vector2D barycenter = null;

        // The area is finite only if the computed quadrilateral area is finite and non-negative.
        // Negative areas indicate that the region is inside-out, with a finite outside surrounded
        // by an infinite inside.
        if (quadrilateralAreaSum >= 0.0 && Double.isFinite(quadrilateralAreaSum)) {
            size = 0.5 * quadrilateralAreaSum;

            if (quadrilateralAreaSum > 0.0) {
                barycenter = Vector2D.of(scaledSumX, scaledSumY).multiply(1.0 / (3.0 * quadrilateralAreaSum));
            }
        }

        return new RegionSizeProperties<>(size, barycenter);
    }

    /** {@inheritDoc} */
    @Override
    protected void invalidate() {
        super.invalidate();

        boundaryPaths = null;
    }

    /** {@inheritDoc} */
    @Override
    protected RegionNode2D createNode() {
        return new RegionNode2D(this);
    }

    /** Return a new {@link RegionBSPTree2D} instance containing the entire space.
     * @return a new {@link RegionBSPTree2D} instance containing the entire space
     */
    public static RegionBSPTree2D full() {
        return new RegionBSPTree2D(true);
    }

    /** Return a new, empty {@link RegionBSPTree2D} instance.
     * @return a new, empty {@link RegionBSPTree2D} instance
     */
    public static RegionBSPTree2D empty() {
        return new RegionBSPTree2D(false);
    }

    /** Construct a new tree from the boundaries in the given boundary source. If no boundaries
     * are present in the given source, their the returned tree contains the full space.
     * @param boundarySrc boundary source to construct a tree from
     * @return a new tree instance constructed from the boundaries in the
     *      given source
     */
    public static RegionBSPTree2D from(final BoundarySource<Segment> boundarySrc) {
        final RegionBSPTree2D tree = RegionBSPTree2D.full();
        tree.insert(boundarySrc);

        return tree;
    }

    /** BSP tree node for two dimensional Euclidean space.
     */
    public static final class RegionNode2D extends AbstractRegionBSPTree.AbstractRegionNode<Vector2D, RegionNode2D> {
        /** Simple constructor.
         * @param tree the owning tree instance
         */
        private RegionNode2D(AbstractBSPTree<Vector2D, RegionNode2D> tree) {
            super(tree);
        }

        /** Get the region represented by this node. The returned region contains
         * the entire area contained in this node, regardless of the attributes of
         * any child nodes.
         * @return the region represented by this node
         */
        public ConvexArea getNodeRegion() {
            ConvexArea area = ConvexArea.full();

            RegionNode2D child = this;
            RegionNode2D parent;

            while ((parent = child.getParent()) != null) {
                Split<ConvexArea> split = area.split(parent.getCutHyperplane());

                area = child.isMinus() ? split.getMinus() : split.getPlus();

                child = parent;
            }

            return area;
        }

        /** {@inheritDoc} */
        @Override
        protected RegionNode2D getSelf() {
            return this;
        }
    }

    /** Class used to project points onto the 2D region boundary.
     */
    private static final class BoundaryProjector2D extends BoundaryProjector<Vector2D, RegionNode2D> {
        /** Simple constructor.
         * @param point the point to project onto the region's boundary
         */
        BoundaryProjector2D(final Vector2D point) {
            super(point);
        }

        /** {@inheritDoc} */
        @Override
        protected Vector2D disambiguateClosestPoint(final Vector2D target, final Vector2D a, final Vector2D b) {
            // return the point with the smallest coordinate values
            final int cmp = Vector2D.COORDINATE_ASCENDING_ORDER.compare(a, b);
            return cmp < 0 ? a : b;
        }
    }
}
