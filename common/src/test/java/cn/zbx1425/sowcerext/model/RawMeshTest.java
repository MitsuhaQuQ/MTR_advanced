package cn.zbx1425.sowcerext.model;

import cn.zbx1425.sowcer.batch.MaterialProp;
import cn.zbx1425.sowcer.math.Matrix4f;
import cn.zbx1425.sowcer.math.Vector3f;
import cn.zbx1425.sowcer.util.DrawContext;
import cn.zbx1425.sowcerext.model.integration.FaceList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawMeshTest {

	private static Vertex vertex(float x, float nx, float u) {
		final Vertex vertex = new Vertex(new Vector3f(x, 0F, 0F), new Vector3f(nx, 0F, 0F));
		vertex.u = u;
		return vertex;
	}

	private static RawMesh mesh(List<Vertex> vertices, int[]... faces) {
		final RawMesh mesh = new RawMesh(new MaterialProp());
		mesh.vertices.addAll(vertices);
		for (final int[] face : faces) {
			mesh.faces.add(new Face(face));
		}
		return mesh;
	}

	private static List<Vertex> list(Vertex... vertices) {
		final List<Vertex> list = new ArrayList<>();
		for (final Vertex vertex : vertices) {
			list.add(vertex);
		}
		return list;
	}

	/**
	 * What distinct did with a hash map, which the current one must match exactly.
	 */
	private static void distinctWithHashMap(RawMesh mesh) {
		final List<Vertex> distinctVertices = new ArrayList<>();
		final Map<Vertex, Integer> lookup = new HashMap<>();
		final LinkedHashSet<Face> distinctFaces = new LinkedHashSet<>();
		for (final Face face : mesh.faces) {
			for (int i = 0; i < face.vertices.length; i++) {
				final Vertex vertex = mesh.vertices.get(face.vertices[i]);
				if (!lookup.containsKey(vertex)) {
					distinctVertices.add(vertex);
					lookup.put(vertex, distinctVertices.size() - 1);
				}
				face.vertices[i] = lookup.get(vertex);
			}
			distinctFaces.add(face);
		}
		mesh.vertices = distinctVertices;
		mesh.faces = new ArrayList<>(distinctFaces);
	}

	private static List<Vertex[]> blazeFaces(RawMesh mesh, Matrix4f matrix) {
		final List<Vertex[]> faces = new ArrayList<>();
		mesh.writeBlazeBuffer(new FaceList(null, false) {
			@Override
			public void addFace(Vertex[] vertices, int color, int light, int overlay) {
				faces.add(vertices);
			}
		}, matrix, 0, 0, 0, new DrawContext());
		return faces;
	}

	private static RawMesh compactableMesh() {
		final Vertex first = new Vertex(new Vector3f(1F, 2F, 3F), new Vector3f(0F, 1F, 0F));
		first.u = 0.25F;
		first.v = 0.75F;
		final Vertex second = new Vertex(new Vector3f(4F, 5F, 6F), new Vector3f(1F, 0F, 0F));
		second.u = 0.5F;
		final Vertex third = new Vertex(new Vector3f(7F, 8F, 9F), new Vector3f(0F, 0F, 1F));
		third.v = 1F;
		return mesh(list(first, second, third), new int[]{0, 1, 2}, new int[]{2, 1, 0});
	}

	@Test
	void aCompactMeshDrawsTheSameAsBefore() {
		final Matrix4f matrix = Matrix4f.translation(10F, 20F, 30F);
		final List<Vertex[]> expected = blazeFaces(compactableMesh(), matrix);
		final RawMesh mesh = compactableMesh();

		mesh.compact();
		final List<Vertex[]> faces = blazeFaces(mesh, matrix);

		assertEquals(0, mesh.vertices.size());
		assertEquals(0, mesh.faces.size());
		assertEquals(3, mesh.getVertexCount());
		assertEquals(2, mesh.getFaceCount());
		assertEquals(expected.size(), faces.size());
		for (int i = 0; i < expected.size(); i++) {
			assertArrayEquals(expected.get(i), faces.get(i));
		}
	}

	@Test
	void aMeshSharingListsKeepsThemWhenTheOtherIsCompacted() {
		final RawMesh mesh = compactableMesh();
		final RawMesh other = mesh.copyForMaterialChanges();

		mesh.compact();

		assertEquals(3, other.vertices.size());
		assertEquals(2, other.faces.size());
	}

	@Test
	void aCopyOfACompactMeshSharesItsData() {
		final RawMesh mesh = compactableMesh();
		mesh.compact();

		assertEquals(2, blazeFaces(mesh.copyForMaterialChanges(), new Matrix4f()).size());
	}

	@Test
	void copyingACompactMeshGivesTheVerticesBack() {
		final RawMesh expected = compactableMesh();
		final RawMesh mesh = compactableMesh();
		mesh.compact();

		final RawMesh copy = mesh.copy();

		assertEquals(expected.vertices, copy.vertices);
		assertEquals(expected.faces, copy.faces);
	}

	@Test
	void copyingACompactMeshLeavesItCompact() {
		final RawMesh mesh = compactableMesh();
		mesh.compact();

		mesh.copy();

		assertEquals(0, mesh.vertices.size());
		assertEquals(3, mesh.getVertexCount());
	}

	@Test
	void changingACopyOfACompactMeshLeavesTheMeshAlone() {
		final RawMesh mesh = compactableMesh();
		mesh.compact();

		mesh.copy().applyTranslation(100F, 0F, 0F);

		assertEquals(new Vector3f(1F, 2F, 3F), mesh.copy().vertices.get(0).position);
	}

	@Test
	void meshesWithTheSameGeometryShareTheirData() {
		final RawMesh mesh = compactableMesh();
		final RawMesh other = compactableMesh();

		mesh.compact();
		other.compact();

		assertTrue(mesh.sharesCompactDataWith(other));
		assertEquals(2, blazeFaces(other, new Matrix4f()).size());
		CompactArrays.clear();
	}

	@Test
	void meshesWithDifferentGeometryKeepTheirOwnData() {
		final RawMesh mesh = compactableMesh();
		final RawMesh other = compactableMesh();
		other.vertices.get(0).u = 0.125F;

		mesh.compact();
		other.compact();

		assertFalse(mesh.sharesCompactDataWith(other));
		CompactArrays.clear();
	}

	@Test
	void changingACopyOfSharedDataLeavesTheOtherMeshAlone() {
		final RawMesh mesh = compactableMesh();
		final RawMesh other = compactableMesh();
		mesh.compact();
		other.compact();

		mesh.copy().applyTranslation(100F, 0F, 0F);

		assertEquals(new Vector3f(1F, 2F, 3F), other.copy().vertices.get(0).position);
		CompactArrays.clear();
	}

	@Test
	void meshesWithoutTrianglesStayAsTheyAre() {
		final RawMesh mesh = mesh(list(vertex(1, 0, 0), vertex(2, 0, 0), vertex(3, 0, 0), vertex(4, 0, 0)), new int[]{0, 1, 2, 3});

		mesh.compact();

		assertEquals(4, mesh.vertices.size());
		assertEquals(1, mesh.faces.size());
	}

	@Test
	void compactingTwiceIsFine() {
		final RawMesh mesh = compactableMesh();

		mesh.compact();
		mesh.compact();

		assertEquals(3, mesh.getVertexCount());
		assertEquals(2, mesh.getFaceCount());
	}

	@Test
	void equalVerticesBecomeOneAndFacesFollow() {
		final RawMesh mesh = mesh(list(vertex(1, 0, 0), vertex(2, 0, 0), vertex(1, 0, 0), vertex(3, 0, 0)), new int[]{0, 1, 3}, new int[]{2, 3, 1});

		mesh.distinct();

		assertEquals(3, mesh.vertices.size());
		assertArrayEquals(new int[]{0, 1, 2}, mesh.faces.get(0).vertices);
		assertArrayEquals(new int[]{0, 2, 1}, mesh.faces.get(1).vertices);
	}

	@Test
	void repeatedFacesGoButFlippedOnesStay() {
		final RawMesh mesh = mesh(list(vertex(1, 0, 0), vertex(2, 0, 0), vertex(3, 0, 0)), new int[]{0, 1, 2}, new int[]{0, 1, 2}, new int[]{2, 1, 0});

		mesh.distinct();

		assertEquals(2, mesh.faces.size());
	}

	@Test
	void verticesNoFaceUsesGo() {
		final RawMesh mesh = mesh(list(vertex(1, 0, 0), vertex(2, 0, 0), vertex(3, 0, 0), vertex(4, 0, 0)), new int[]{0, 1, 2});

		mesh.distinct();

		assertEquals(3, mesh.vertices.size());
	}

	@Test
	void anEmptyMeshStaysEmpty() {
		final RawMesh mesh = mesh(list());

		mesh.distinct();

		assertEquals(0, mesh.vertices.size());
		assertEquals(0, mesh.faces.size());
	}

	@Test
	void colourAndLightDoNotSeparateVertices() {
		final Vertex first = vertex(1, 0, 0);
		final Vertex second = vertex(1, 0, 0);
		first.color = 1;
		second.color = 2;
		second.light = 3;

		final RawMesh mesh = mesh(list(first, second, vertex(2, 0, 0), vertex(3, 0, 0)), new int[]{0, 2, 3}, new int[]{1, 3, 2});
		mesh.distinct();

		assertEquals(3, mesh.vertices.size());
		assertSame(first, mesh.vertices.get(0));
	}

	@Test
	void meshesSharingListsStaySameAfterDistinct() {
		final RawMesh mesh = mesh(list(vertex(1, 0, 0), vertex(1, 0, 0), vertex(2, 0, 0), vertex(3, 0, 0)), new int[]{1, 2, 3});
		final List<Vertex> sharedVertices = mesh.vertices;
		final List<Face> sharedFaces = mesh.faces;

		mesh.distinct();

		assertSame(sharedVertices, mesh.vertices);
		assertSame(sharedFaces, mesh.faces);
	}

	@Test
	void hashCodeFollowsEquals() {
		assertEquals(vertex(1, 2, 3), vertex(1, 2, 3));
		assertEquals(vertex(1, 2, 3).hashCode(), vertex(1, 2, 3).hashCode());
		assertNotEquals(vertex(1, 2, 3), vertex(1, 2, 4));
		// The sign of zero has always kept vertices apart, so hashing must not join them
		assertNotEquals(vertex(0F, 0, 0), vertex(-0F, 0, 0));
		assertNotEquals(vertex(0, 0, 0F), vertex(0, 0, -0F));
	}

	@Test
	void largeMeshesMatchTheHashMapExactly() {
		final Random random = new Random(1);
		final List<Vertex> vertices = new ArrayList<>();
		for (int i = 0; i < 50000; i++) {
			vertices.add(vertex(random.nextInt(2000), random.nextInt(3), random.nextInt(4)));
		}

		final List<int[]> faces = new ArrayList<>();
		for (int i = 0; i < 100000; i++) {
			faces.add(new int[]{random.nextInt(vertices.size()), random.nextInt(vertices.size()), random.nextInt(vertices.size())});
		}
		final RawMesh mesh = mesh(vertices);
		final RawMesh expected = mesh(vertices);
		faces.forEach(face -> {
			mesh.faces.add(new Face(face.clone()));
			expected.faces.add(new Face(face.clone()));
		});

		mesh.distinct();
		distinctWithHashMap(expected);

		assertEquals(expected.vertices.size(), mesh.vertices.size());
		for (int i = 0; i < expected.vertices.size(); i++) {
			assertSame(expected.vertices.get(i), mesh.vertices.get(i));
		}
		assertEquals(expected.faces.size(), mesh.faces.size());
		for (int i = 0; i < expected.faces.size(); i++) {
			assertArrayEquals(expected.faces.get(i).vertices, mesh.faces.get(i).vertices);
		}
	}

	@Test
	void missingNormalsAreWorkedOutAndExistingOnesKept() {
		final Vertex withNormal = new Vertex(new Vector3f(1F, 0F, 0F), new Vector3f(0F, 0F, 1F));
		final RawMesh mesh = mesh(list(new Vertex(new Vector3f(0F, 0F, 0F)), withNormal, new Vertex(new Vector3f(0F, 0F, 1F))), new int[]{0, 1, 2});

		mesh.generateNormals();
		mesh.distinct();

		assertEquals(new Vector3f(0F, -1F, 0F), mesh.vertices.get(0).normal);
		assertEquals(new Vector3f(0F, 0F, 1F), mesh.vertices.get(1).normal);
		assertEquals(new Vector3f(0F, -1F, 0F), mesh.vertices.get(2).normal);
	}

	@Test
	void aFlatFaceGetsAnUpwardNormal() {
		final RawMesh mesh = mesh(list(new Vertex(new Vector3f(0F, 0F, 0F)), new Vertex(new Vector3f(1F, 0F, 0F)), new Vertex(new Vector3f(2F, 0F, 0F))), new int[]{0, 1, 2});

		mesh.generateNormals();

		assertEquals(new Vector3f(0F, 1F, 0F), mesh.vertices.get(0).normal);
	}

	@Test
	void afterDistinctMovingAMeshMovesEachVertexOnce() {
		final Vertex shared = new Vertex(new Vector3f(1F, 0F, 0F), new Vector3f(0F, 1F, 0F));
		final RawMesh mesh = mesh(
				list(shared, new Vertex(new Vector3f(2F, 0F, 0F), new Vector3f(0F, 1F, 0F)), new Vertex(new Vector3f(3F, 0F, 1F), new Vector3f(0F, 1F, 0F)), new Vertex(new Vector3f(1F, 0F, 2F), new Vector3f(0F, 1F, 0F))),
				new int[]{0, 1, 2}, new int[]{0, 2, 3}
		);

		mesh.generateNormals();
		mesh.distinct();
		mesh.applyTranslation(10F, 0F, 0F);

		assertEquals(4, mesh.vertices.size());
		assertEquals(new Vector3f(11F, 0F, 0F), mesh.vertices.get(0).position);
	}

	@Test
	void verticesWithDifferentNormalsDoNotShareAPosition() {
		final RawMesh mesh = mesh(
				list(new Vertex(new Vector3f(0F, 0F, 0F)), new Vertex(new Vector3f(1F, 0F, 0F)), new Vertex(new Vector3f(0F, 0F, 1F)), new Vertex(new Vector3f(0F, 1F, 0F))),
				new int[]{0, 1, 2}, new int[]{0, 3, 1}
		);

		mesh.generateNormals();
		mesh.distinct();

		assertEquals(mesh.vertices.get(0).position, mesh.vertices.get(3).position);
		assertNotSame(mesh.vertices.get(0).position, mesh.vertices.get(3).position);
	}
}
