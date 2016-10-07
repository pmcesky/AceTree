package org.rhwlab.snight;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.StringTokenizer;

import org.rhwlab.acetree.AceTree;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.transform.Rotate;


/**
 * Called to assign names on cell division
 * Previously: embryos in strict starting orientations
 * Revised: Any starting orientation can be used to calculate names
 * 
 * Original @author 
 * 
 * Revision @author Braden Katzman
 * Date: July 2016 - September 2016
 */

public class DivisionCaller {

	Hashtable<String, Rule>		iRulesHash;
	Hashtable<String, String>		iSulstonHash;
	String			iAxis;
	String			iAxisUse;
	double			iZPixRes;
	MeasureCSV		iMeasureCSV;
	double			iAng;
	double			iEMajor;
	double			iEMinor;
	double			iZSlope;
	Point2D			iAngVec;
	double			iDMajor;
	double			iDMinor;
	double			iDSlope;
	boolean			iDebug;
	double [] 		iDaCorrected;

	private BooleanProperty auxInfoVersion2;

	// vectors, angles, axes used for rotation to canonical orientation
	private double[] AP_orientation_vec;
	private double[] LR_orientation_vec;
	private Point3D AP_orientation_vector;
	private Point3D LR_orientation_vector;
	private Point3D DV_orientation_vector;

	// angles of rotations
	private double angleOfRotationAP;
	private double angleOfRotationLR;

	// rotation axes
	private Point3D rotationAxisAP;
	private Point3D rotationAxisLR;

	// rotation matrices
	private Rotate rotMatrixAP;
	private Rotate rotMatrixLR;

	/*
	 * TODO
	 * revise this once same results are produced by canonicaltransform 
	 * and pass the transform class to this constructor
	 * 
	 */
	public DivisionCaller(MeasureCSV measureCSV) {
		this.iMeasureCSV = measureCSV;
		this.iRulesHash = new Hashtable<String, Rule>();
		readNewRules();
		readSulstonRules();

		/*
		 * initialize based on AuxInfo v1.0 or v2.0
		 */
		this.auxInfoVersion2 = new SimpleBooleanProperty();
		this.auxInfoVersion2.set(MeasureCSV.isAuxInfoV2());
		this.auxInfoVersion2.addListener(new ChangeListener<Boolean>() {
			@Override
			public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
				/*
				 * If AuxInfo v2.0 fails at any point, get the axis and angle from the AuxInfo v1.0
				 */
				if (!newValue) {
					iAxis = iMeasureCSV.iMeasureHash_v1.get(MeasureCSV.att_v1[MeasureCSV.AXIS_v1]);

					String sang = iMeasureCSV.iMeasureHash_v1.get("ang");
					if (sang.length() > 0) {
						iAng = Math.toRadians(-Double.parseDouble(sang));
					}

					iAngVec = new Point2D(Math.cos(iAng), Math.sin(iAng));
				}
			}
		});



		System.out.println(" ");
		if (!(auxInfoVersion2.get())) {
			System.out.println("Using AuxInfo version 1.0");
			this.iAxis = iMeasureCSV.iMeasureHash.get(MeasureCSV.att_v1[MeasureCSV.AXIS_v1]);

			String zpixres = iMeasureCSV.iMeasureHash.get(MeasureCSV.att_v1[MeasureCSV.ZPIXRES_v1]);
			if (zpixres != null) {
				this.iZPixRes = Double.parseDouble(zpixres);
			} else {
				this.iZPixRes = 1; //default to this
			}

		} else {
			System.out.println("Using AuxInfo version 2.0");

			// read the initial orientations
			String AP_orientation_vec_str = measureCSV.iMeasureHash.get(MeasureCSV.att_v2[MeasureCSV.AP_ORIENTATION]);
			String LR_orientation_vec_str = measureCSV.iMeasureHash.get(MeasureCSV.att_v2[MeasureCSV.LR_ORIENTATION]);

			this.AP_orientation_vec = new double[THREE];
			StringTokenizer st = new StringTokenizer(AP_orientation_vec_str, " ");
			if (st.countTokens() != THREE) System.err.println("AP orientation vector is incorrect size");
			AP_orientation_vec[0] = Double.parseDouble(st.nextToken());
			AP_orientation_vec[1] = Double.parseDouble(st.nextToken());
			AP_orientation_vec[2] = Double.parseDouble(st.nextToken());

			this.LR_orientation_vec = new double[THREE];
			StringTokenizer st2 = new StringTokenizer(LR_orientation_vec_str, " ");
			if (st2.countTokens() != THREE) System.err.println("LR orientation vector is incorrect size");
			LR_orientation_vec[0] = Double.parseDouble(st2.nextToken());
			LR_orientation_vec[1] = Double.parseDouble(st2.nextToken());
			LR_orientation_vec[2] = Double.parseDouble(st2.nextToken());

			// create vector objects from initial orientations
			this.AP_orientation_vector = new Point3D(AP_orientation_vec[0], AP_orientation_vec[1], AP_orientation_vec[2]);
			this.LR_orientation_vector = new Point3D(LR_orientation_vec[0], LR_orientation_vec[1], LR_orientation_vec[2]);

			initOrientation();
			confirmOrientation();

			this.iZPixRes = Double.parseDouble(iMeasureCSV.iMeasureHash.get(MeasureCSV.att_v2[MeasureCSV.ZPIXRES_v2]));
		}

		getScalingParms();

		// uncomment this line to override AuxInfo v2.0 functionality and revert to v1.0
		//		this.auxInfoVersion2.set(false);
	}

	/**
	 * - Normalizes input vectors
	 * - Finds the DV orientation (cross product of AP and LR)
	 * - Finds the Axis-Angle representation of the rotation from AP initial to AP canonical
	 * - Finds the Axis-Angle representation of the rotation from LR initial to LR canonical
	 * - Builds a rotation matrix (JavaFX Rotate object) for AP
	 * - Builds a rotation matrix (JavaFX Rotate object) for LR
	 * 
	 * 
	 * *** There is a degenerate case of the axis-angle representation that needs to be handled manually:
	 * - When the cross product of the two vectors is <0,0,0>, the resulting rotation matrix will not rotate
	 * 		the vector even if there is an angle between the two vectors
	 * - A <0,0,0> vector will result when the two vectors are in the same plane. Thus, when this happens, use
	 *		the two vectors to figure out which plane they are in and manually set the rotation matrix to move
	 *		around an axis perpendicular to the plane in which the two vectors lie. e.g.:
	 *			- rotate around the z-axis if the vectors are in the xy-plane
	 * 
	 * @author: Braden Katzman (July-August 2016)
	 */
	private void initOrientation() {
		System.out.println(" ");
		System.out.println("Initialized orientations with AuxInfo v2.0");
		if (AP_orientation_vec == null || LR_orientation_vec == null || AP_orientation_vec.length != THREE || LR_orientation_vec.length != THREE) {
			System.err.println("Incorrect AP, LR orientations from AuxInfo");
			return;
		}

		// normalize
		this.AP_orientation_vector = AP_orientation_vector.normalize();
		this.LR_orientation_vector = LR_orientation_vector.normalize();

		// cross product for DV orientation vector --> init with AP_orientation coords and then cross with LR
		this.DV_orientation_vector = new Point3D(AP_orientation_vector.getX(), AP_orientation_vector.getY(), AP_orientation_vector.getZ());
		this.DV_orientation_vector.crossProduct(LR_orientation_vector);

		// axis angle rep. of AP --> init with AP_orientation coords and then cross with AP canonical orientation
		this.rotationAxisAP = new Point3D(AP_orientation_vector.getX(), AP_orientation_vector.getY(), AP_orientation_vector.getZ());
		this.rotationAxisAP = rotationAxisAP.crossProduct(AP_can_or);
		this.rotationAxisAP = roundVecCoords(rotationAxisAP);
		this.rotationAxisAP = rotationAxisAP.normalize();
		this.angleOfRotationAP = angBWVecs(AP_orientation_vector, AP_can_or);

		// check for degenerate case --> make sure angle of nonzero first to ensure the dataset isn't just already in canonical orientation
		if (angleOfRotationAP != 0 && rotationAxisAP.getX() == 0 && rotationAxisAP.getY() == 0 && rotationAxisAP.getZ() == 0) {
			// ensure that the AP orientation vector is in fact a vector in the xy plane
			if (AP_orientation_vector.getX() != 0 && AP_orientation_vector.getY() == 0 && AP_orientation_vector.getZ() == 0) {
				System.out.println("Degenerate case of axis angle rotation, rotation only about z axis in xy plane");

				// make the z axis the axis of rotation
				this.rotationAxisAP = new Point3D(0., 0., -1.);
			}
		}

		// build rotation matrix for AP
		this.rotMatrixAP = new Rotate(radiansToDegrees(this.angleOfRotationAP),
				new Point3D(rotationAxisAP.getX(), rotationAxisAP.getY(), rotationAxisAP.getZ()));

		// axis angle rep. of LR		
		this.rotationAxisLR = new Point3D(LR_orientation_vector.getX(), LR_orientation_vector.getY(), LR_orientation_vector.getZ());
		this.rotationAxisLR = rotationAxisLR.crossProduct(LR_can_or);
		this.rotationAxisLR = roundVecCoords(rotationAxisLR);
		this.rotationAxisLR = rotationAxisLR.normalize();
		this.angleOfRotationLR = angBWVecs(LR_orientation_vector, LR_can_or);

		// check for degenerate case --> make sure angle of nonzero first to ensure the dataset isn't just already in canonical orientation
		if (angleOfRotationLR != 0 && rotationAxisLR.getX() == 0 && rotationAxisLR.getY() == 0 && rotationAxisLR.getZ() == 0) {
			// ensure that the LR orientation vector is in fact a vector in the yz plane
			if (LR_orientation_vector.getX() == 0 && LR_orientation_vector.getY() == 0 && LR_orientation_vector.getZ() != 0) {
				System.out.println("Degenerate case of axis angle rotation, rotation only about x axis in yz plane");

				// make the x axis the axis of rotation
				this.rotationAxisLR = new Point3D(-1., 0., 0.);
			}
		}

		// build rotation matrix for LR
		this.rotMatrixLR = new Rotate(radiansToDegrees(this.angleOfRotationLR),
				new Point3D(rotationAxisLR.getX(), rotationAxisLR.getY(), rotationAxisLR.getZ()));


		//		System.out.println(" ");
		//		System.out.println("AP orientation: <" + AP_orientation_vector.getX() + ", " + AP_orientation_vector.getY() + ", " + AP_orientation_vector.getZ() + ">");
		//		System.out.println("LR orientation: <" + LR_orientation_vector.getX() + ", " + LR_orientation_vector.getY() + ", " + LR_orientation_vector.getZ() + ">");
		//		System.out.println("DV orientation: <" + DV_orientation_vector.getX() + ", " + DV_orientation_vector.getY() + ", " + DV_orientation_vector.getZ() + ">");

		//		System.out.println(" ");
		//		System.out.println("AP angle: " + angleOfRotationAP);

		//		System.out.println(" ");
		//		System.out.println("LR angle: " + angleOfRotationLR);
	}

	/**
	 * Convert radians to degrees
	 * 
	 * @param radians
	 * @return degrees
	 */
	private double radiansToDegrees(double radians) {
		if (Double.isNaN(radians)) return 0.;

		return radians * (180/Math.PI);
	}

	/**
	 * Finds the angle between two vectors using the formula:
	 * acos( dot(vec_1, vec_2) / (length(vec_1) * length(vec_2)) )
	 * 
	 * @param v1
	 * @param v2
	 * @return the angle between the two input vectors
	 */
	private double angBWVecs(Point3D v1, Point3D v2) {
		if (v1 == null || v2 == null) return 0.;

		double ang = Math.acos(v1.dotProduct(v2) / (vecLength(v1) * vecLength(v2)));

		return (Double.isNaN(ang)) ? 0. : ang;
	}

	/**
	 * JavaFX does not have a built in Vector class, so we use Point3D as a substitute
	 * This method treats a Point3D as a vector and finds its length
	 * 
	 * @param v - the vector represented by a Point3D
	 * @return the length of the vector
	 */
	private double vecLength(Point3D v) {
		if (v == null) return 0.;

		return Math.sqrt((v.getX()*v.getX()) + (v.getY()*v.getY()) + (v.getZ()*v.getZ()));
	}

	/**
	 * After the rotations are initialized, this method is called to confirm that the rotations
	 * rotate the initial orientation of the dataset into canonical orientation. If this fails,
	 * the flag denoting the version of AuxInfo in use (1.0 or 2.0) is set to false (denoting version
	 * 1.0).
	 * 
	 * @author Braden Katzman
	 * Date: 8/15/16
	 */
	private void confirmOrientation() {
		Point3D AP_orientation_pt = rotMatrixAP.deltaTransform(AP_orientation_vector.getX(), AP_orientation_vector.getY(), AP_orientation_vector.getZ());
		AP_orientation_pt = AP_orientation_pt.normalize();
		Point3D AP_orientation_test_vec = new Point3D(AP_orientation_pt.getX(), AP_orientation_pt.getY(), AP_orientation_pt.getZ());
		AP_orientation_test_vec = roundVecCoords(AP_orientation_test_vec);
		if (!AP_can_or.equals(AP_orientation_test_vec)) {
			System.out.println("AP orientation incorrectly rotated to: <" + 
					AP_orientation_test_vec.getX() + ", " + AP_orientation_test_vec.getY() + ", " + AP_orientation_test_vec.getZ() + ">");
			System.out.println("Reverting to AuxInfo v1.0");

			auxInfoVersion2.set(false);
			return;
		}

		Point3D LR_orientation_pt = rotMatrixLR.deltaTransform(LR_orientation_vector.getX(), LR_orientation_vector.getY(), LR_orientation_vector.getZ());
		LR_orientation_pt = LR_orientation_pt.normalize();
		Point3D LR_orientation_test_vec = new Point3D(LR_orientation_pt.getX(), LR_orientation_pt.getY(), LR_orientation_pt.getZ());
		LR_orientation_test_vec = roundVecCoords(LR_orientation_test_vec);
		if (!LR_can_or.equals(LR_orientation_test_vec)) {
			System.out.println("LR orientation incorrectly rotated to: <" + 
					LR_orientation_test_vec.getX() + ", " + LR_orientation_test_vec.getY() + ", " + LR_orientation_test_vec.getZ() + ">");
			System.out.println("Reverting to AuxInfo v1.0");

			auxInfoVersion2.set(false);
			return;
		}

		System.out.println("Confirmed rotations from initial AP, LR to canonical");
	}

	private void getScalingParms() {		
		if (!(auxInfoVersion2.get())) {
			initAng();
		}

		String smaj = iMeasureCSV.iMeasureHash.get("maj");
		if (smaj != null && smaj.length() > 0) {
			iEMajor = Double.parseDouble(smaj);
		} else {
			if (MeasureCSV.isAuxInfoV2()) {
				iEMajor = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.EMAJOR_v2]);
			} else {
				iEMajor = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.EMAJOR_v1]);
			}
		}

		String smin = iMeasureCSV.iMeasureHash.get("min");
		if (smin != null && smin.length() > 0) {
			iEMinor = Double.parseDouble(smin);
		} else {
			if (MeasureCSV.isAuxInfoV2()) {
				iEMinor = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.EMINOR_v2]);
			} else {
				iEMinor = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.EMINOR_v1]);
			}	
		}


		String szslope = iMeasureCSV.iMeasureHash.get("zslope");
		if (szslope != null && szslope.length() > 0) {
			iZSlope = Double.parseDouble(szslope);
		} else {
			if (auxInfoVersion2.get()) {
				iZSlope = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.ZSLOPE_v2]);
			} else {
				iZSlope = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.ZSLOPE_v1]);
			}
		}

		if (MeasureCSV.isAuxInfoV2()) {
			iDMajor = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.EMAJOR_v2]);
			iDMinor = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.EMINOR_v2]);
			iDSlope = Double.parseDouble(MeasureCSV.defaultAtt_v2[MeasureCSV.ZSLOPE_v2]);
		} else {
			iDMajor = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.EMAJOR_v1]);
			iDMinor = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.EMINOR_v1]);
			iDSlope = Double.parseDouble(MeasureCSV.defaultAtt_v1[MeasureCSV.ZSLOPE_v1]);
		}
	}

	/**
	 * Initializes the "ang" field for assigning names if AuxInfo version 1.0 is being used
	 */
	private void initAng() {
		if (!(auxInfoVersion2.get())) {
			String sang = iMeasureCSV.iMeasureHash.get("ang");
			if (sang != null) {
				if (sang.length() > 0) {
					iAng = Math.toRadians(-Double.parseDouble(sang));
				}
			}

			iAngVec = new Point2D(Math.cos(iAng), Math.sin(iAng));
		}
	}

	/**
	 * Only used for debugging purposes
	 */
	public void showMeasureCSV() {
		Enumeration<?> e = iMeasureCSV.iMeasureHash.keys();
		while (e.hasMoreElements()) {
			String key = (String)e.nextElement();
			String value = iMeasureCSV.iMeasureHash.get(key);
			println("showMeasureCSV, " + key + CS + value);
		}
	}

	public String getRuleString(String parent) {
		Rule r = iRulesHash.get(parent);
		if (r == null) return "";
		else return r.toString();
	}

	/**
	 * Given a parent Nucleus, return Rule if already created, create if not
	 * @param parent
	 * @return
	 */
	private Rule getRule(Nucleus parent) {
		Rule r = iRulesHash.get(parent.identity);

		/*
		 * If no rule is found, create rule based on division direction and axis
		 */
		if (r == null) {
			String pname = parent.identity;
			//System.out.println("DivisionCaller.getRule parent identity: "+pname);
			String sulston = iSulstonHash.get(pname);

			//System.out.println("Sulston for: " + pname + " is --> " + sulston);

			//If no name, set default as 'a'
			if (sulston == null || pname.startsWith("Nuc")) {
				sulston = "a";
			} else { //used the first letter of the sulston name
				sulston = sulston.substring(0, 1);
			}

			//System.out.println("Using first letter: '" +  sulston + "' for daughter_1 and: '" + complement(sulston.charAt(0)) + "' for daughter_2");

			// append parent identity with sulston letter for daughter_1 sulston name 
			String sdau1 = parent.identity + sulston;
			//System.out.println("Sulston daughter_1 for parent: " + pname + ", is --> " + sdau1);

			// get the letter representing the division opposite of this division
			char c = complement(sulston.charAt(0));

			// set the daughter cell's sulston name with parent identity and complement of division
			String sdau2 = parent.identity + c;
			//System.out.println("Sulston daughter_2 for parent: " + pname + ", is --> " + sdau2);

			// set the xyz axis of this rule based on sulston first letter
			/*
			 * If the first daughter divides in the anterior direction, the Rule vector is <1,0,0>
			 * If the first daughter divides in the left direction, the Rule vector is <0,0,1>
			 * If the first daughter divides in the dorsal direction, the Rule vector is <0,1,0> (else condition always seems to be when sulston equals "d")
			 */
			int x = 0;
			int y = 0;
			int z = 0;
			if (sulston.equals("a")) {
				x = 1;
			} else if (sulston.equals("l")) {
				z = 1;
			} else { //usually "d"
				y = 1;
			}

			// append a 0 to the sulston name
			sulston += "0";

			// create a rule for this parent which contains parent name, the directional suslton letter for the first daughter, and the vector corresponding
			r = new Rule(pname, sulston, sdau1, sdau2, x, y, z);

			if (!(auxInfoVersion2.get())) {
				// assuming dummy rules are late in embryonic development
				// introduce rotation
				if (iAxis.equals("ADL"))
					iAxisUse = "ARD";
				if (iAxis.equals("AVR"))
					iAxisUse = "ALV";
				if (iAxis.equals("PDR"))
					iAxisUse = "PLD";
				if (iAxis.equals("PVL"))
					iAxisUse = "PRV";
				if (iAxis == null)
					iAxisUse = MeasureCSV.defaultAtt_v1[MeasureCSV.AXIS_v1];
			}
		}
		return r;
	}

	public double getDotProduct(Nucleus parent, Nucleus dau1, Nucleus dau2) {
		Rule r = getRule(parent);
		return getDotProduct(parent, dau1, dau2, r);
	}

	public double [] getDaCorrected() {
		return iDaCorrected;
	}


	/**
	 * Determines the dot product between the vector in the Rule and the vector between the daughter cells
	 * The vector between the daughter cells is corrected and rotated before calculating dot
	 * 
	 * @param parent
	 * @param dau1
	 * @param dau2
	 * @param r
	 * @return
	 */
	private double getDotProduct(Nucleus parent, Nucleus dau1, Nucleus dau2, Rule r) {
		if (!(auxInfoVersion2.get())) {
			iAxisUse = iAxis;
		}

		// create a temporary template vector from the xyz coordinates of the rule
		Point3D template = new Point3D(r.iX, r.iY, r.iZ);

		// find the vector between the daughter cells, with corrections and rotations induced
		double [] daCorrected = diffsCorrected(dau1, dau2);

		// update the instance var
		iDaCorrected = daCorrected;

		// create vector from daughter vector coordinates
		Point3D sample = new Point3D(daCorrected[0], daCorrected[1], daCorrected[2]);

		// normalize the vector
		sample = sample.normalize();

		/*
		 * find and return the dot product of the normalized, corrected, and rotated
		 * vector between the two daughter cells
		 */
		double dotCorrected = template.dotProduct(sample);
		double dot = dotCorrected;
		Double Dot = new Double(dot);
		if (Dot.isNaN()) dot = 0;
		return dot;
	}

	/**
	 * Assigns names given a parent cell and its daughters based on their locations relative to the canonical axis
	 * 
	 * @param parent
	 * @param dau1
	 * @param dau2
	 * @return
	 */
	public void assignNames(Nucleus parent, Nucleus dau1, Nucleus dau2) {
		//		System.out.println("Assigning names in DivisionCaller.java for parent: " + parent.identity);
		String newd1 = "";
		String newd2 = "";


		// get an existing rule or create a new one based on the division direction of the daughters from the given parent
		Rule r = getRule(parent);

		// find the dot product between the vector in the Rule and the corrected, rotated vector between the daughter cells
		double dot = getDotProduct(parent, dau1, dau2, r);
		if (dot > 0) {
			newd1 = r.iDau1;
			newd2 = r.iDau2;
		}
		else {
			newd1 = r.iDau2;
			newd2 = r.iDau1;
		}
		dau1.identity = newd1;
		dau2.identity = newd2;
	}

	/**
	 * Find the vector between the daughter cells with corrections and rotations
	 * 
	 * @param d1
	 * @param d2
	 * @return the vector between the two daughters, corrected by constants and axis in use
	 */
	private double [] diffsCorrected(Nucleus d1, Nucleus d2) {
		double [] da = new double[THREE];

		// take the difference between the coordinates of the daughter cells
		da[0] = d2.x - d1.x;
		da[1] = d2.y - d1.y;
		da[2] = d2.z - d1.z;

		// scale the z coordinate difference by the z pixel resolution i.e. the z scale
		da[2] *= iZPixRes;
		//if (iDebug) println("diffs, " + fmt4(da[0]) + CS + fmt4(da[1]) + CS + fmt4(da[2]));

		// induce rotations with corrections and scaling
		measurementCorrection(da);

		if (!(auxInfoVersion2.get())) {
			if (iAxisUse == null)
				iAxisUse = MeasureCSV.defaultAtt_v1[MeasureCSV.AXIS_v1];
			if (iAxisUse.equals("AVR")) {
				da[1] *= -1;
				da[2] *= -1;
			} else if (iAxisUse.equals("PVL")) {
				da[0] *= -1;
				da[1] *= -1;
			} else if (iAxisUse.equals("PDR")) {
				da[0] *= -1;
				da[2] *= -1;
			} else if (iAxisUse.equals("ARD")) {
				da[1] *= -1;
			} else if (iAxisUse.equals("ALV")) {
				da[2] *= -1;
			} else if (iAxisUse.equals("PLD")) {
				da[0] *= -1;
			} else if (iAxisUse.equals("PRV")) {
				da[1] *= -1;
				da[2] *= -1;
			}
		}

		return da;
	}

	/**
	 * Apply the rotations and make corrections
	 * 
	 * @param da
	 */
	private void measurementCorrection(double [] da) {
		// correct for angle
		if (auxInfoVersion2.get()) {
			handleRotation_V2(da);
		} else {
			double [] dxy = handleRotation_V1(da[0], da[1], iAng);
			da[0] = dxy[0];
			da[1] = dxy[1];
		}

		// correct for x stretch
		da[0] *= (iEMajor/iDMajor);
		// correct for y stretch
		da[1] *= (iEMinor/iDMinor);
		// correct for z stretch
		da[2] *= (iZSlope/iDSlope);
	}

	/**
	 * Rotate the vector between divided cells by the AuxInfo version 2.0 scheme
	 * - Applies both rotation matrices that are computed at the start to rotate into canonical orientation
	 * @param vec - the vector between daughter cells after division
	 */
	private void handleRotation_V2(double[] vec) {
		// make local copy
		double[] vec_local = new double[3];
		vec_local[0] = vec[0];
		vec_local[1] = vec[1];
		vec_local[2] = vec[2];

		// get Point3D from applying first rotation (AP) to vector
		Point3D daughterCellsPt3d_firstRot = rotMatrixAP.deltaTransform(vec_local[0], vec_local[1], vec_local[2]);

		//		daughterCellsPt3d_firstRot = roundVecCoords(daughterCellsPt3d_firstRot);

		// update vec_local
		vec_local[0] = daughterCellsPt3d_firstRot.getX();
		vec_local[1] = daughterCellsPt3d_firstRot.getY();
		vec_local[2] = daughterCellsPt3d_firstRot.getZ();

		// get Point3D from applying second rotation (LR) to vector
		Point3D daughterCellsPt3d_bothRot = rotMatrixLR.deltaTransform(vec_local[0], vec_local[1], vec_local[2]);

		//		daughterCellsPt3d_bothRot = roundVecCoords(daughterCellsPt3d_bothRot);

		// update vec_local
		vec_local[0] = daughterCellsPt3d_bothRot.getX();
		vec_local[1] = daughterCellsPt3d_bothRot.getY();
		vec_local[2] = daughterCellsPt3d_bothRot.getZ();

		// error handling
		if (Double.isNaN(vec_local[0]) || Double.isNaN(vec_local[1]) || Double.isNaN(vec_local[2])) return;

		//		System.out.println("<" + vec[0] + ", " + vec[1] + ", " + vec[2] + "> to <" + vec_local[0] + ", " + vec_local[1] + ", " + vec_local[2] + ">");
		// update parameter vector
		vec[0] = vec_local[0];
		vec[1] = vec_local[1];
		vec[1] = vec_local[2];
	}


	/**
	 * Rotate the x and y coordinates of the vector between divided cells by the AuxInfo version 1.0 scheme
	 * ***Note: this method is used once under the AuxInfo v2.0 scheme to rotate the projection of the
	 * 		 AP axis onto the xy plane to canonical orientation
	 * 
	 * @param x
	 * @param y
	 * @param ang - in radians
	 * @return
	 */
	public static double [] handleRotation_V1(double x, double y, double ang) {
		double cosang = Math.cos(ang);
		double sinang = Math.sin(ang);
		double denom = cosang * cosang + sinang * sinang;
		double xpnum = x * cosang + y * sinang;
		double xp = xpnum / denom;
		double yp = (y - xp * sinang) / cosang;
		double [] da = new double[2];
		da[0] = xp;
		da[1] = yp;
		return da;
	}

	/**
	 * Round the coordinates of a vector to a whole number if within a certain threshold to that number
	 * ZERO_THRESHOLD defined at bottom of file
	 * 
	 * @param vec - the vector to be rounded
	 * @return the updated vector in the form of a Point3D
	 */
	private Point3D roundVecCoords(Point3D vec) {
		Point3D tmp = new Point3D(Math.round(vec.getX()), Math.round(vec.getY()), Math.round(vec.getZ()));

		vec = new Point3D(
				(Math.abs(vec.getX() - tmp.getX()) <= ZERO_THRESHOLD) ? tmp.getX() : vec.getX(),
						(Math.abs(vec.getY() - tmp.getY()) <= ZERO_THRESHOLD) ? tmp.getY() : vec.getY(),		
								(Math.abs(vec.getZ() - tmp.getZ()) <= ZERO_THRESHOLD) ? tmp.getZ() : vec.getZ()
				);

		return vec;
	}	

	private void readNewRules() {
		URL url = AceTree.class.getResource("/org/rhwlab/snight/NewRules.txt");
		InputStream istream = null;
		try {
			istream = url.openStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(istream));
			String s;
			br.readLine(); //toss the header
			while (br.ready()) {
				s = br.readLine();
				if (s.length() == 0) continue;
				//println("readNewRules, " + s);
				String [] sa = s.split(TAB);
				Rule r = new Rule(sa);
				iRulesHash.put(sa[0], r);
			}
			br.close();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}


	public void readSulstonRules() {
		Hashtable<String, String> namingHash = new Hashtable<String, String>();
		URL url = AceTree.class.getResource("/org/rhwlab/snight/namesHash.txt");
		InputStream istream = null;
		try {
			istream = url.openStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(istream));
			String s;
			while (br.ready()) {
				s = br.readLine();
				if (s.length() == 0) continue;
				String [] sa = s.split(",");
				namingHash.put(sa[0], sa[1]);
			}
			br.close();
		} catch(Exception e) {
			e.printStackTrace();
		}
		iSulstonHash = namingHash;

	}

	/**
	 * Returns the opposite division given a division letter
	 * @param x - the character representing the direction of the division (A,P,D,V,L,R)
	 * @return - the division direction opposite of x
	 */
	private char complement(char x) {
		switch(x) {
		case 'a':
			return 'p';
		case 'p':
			return 'a';
		case 'd':
			return 'v';
		case 'v':
			return 'd';
		case 'l':
			return 'r';
		case 'r':
			return 'l';

		}
		return 'g';
	}


	public class Rule {
		double  DOTTOL = 0.6;
		String 	iParent;
		String  iRule;
		String	iDau1;
		String  iDau2;
		public double	iX;
		public double	iY;
		public double   iZ;

		public Rule(String [] sa) {
			iParent = sa[0];
			iRule = sa[1];
			iDau1 = sa[2];
			iDau2 = sa[3];
			iX = Double.parseDouble(sa[4]);
			iY = Double.parseDouble(sa[5]);
			iZ = Double.parseDouble(sa[6]);
		}

		// constructor for default rule
		public Rule(String parent, String rule, String dau1, String dau2, double x, double y, double z) {
			iParent = parent;
			iRule = rule;
			iDau1 = dau1;
			iDau2 = dau2;
			iX = x;
			iY = y;
			iZ = z;
			//println("Rule, default rule in use");
		}

		@Override
		public String toString() {
			StringBuffer sb = new StringBuffer();
			sb.append(iParent);
			//sb.append(CS + iRule);
			sb.append(C + iDau1);
			sb.append(C + iDau2);
			sb.append(C + fmt4(iX));
			sb.append(C + fmt4(iY));
			sb.append(C + fmt4(iZ));
			return sb.toString();
		}

	}

	public static void test() {
		double x = 20;
		double y = 10;
		double deg = -15;
		double ang = Math.toRadians(deg);
		double cosang = Math.cos(ang);
		double sinang = Math.sin(ang);
		double denom = cosang * cosang + sinang * sinang;
		double xpnum = x * cosang + y * sinang;
		double xp = xpnum / denom;
		double yp = (y - xp * sinang) / cosang;
		println("test, " + x + CS + y + CS + fmt4(xp) + CS + fmt4(yp));
		double check0 = x * x + y * y;
		double check1 = xp * xp + yp * yp;
		println("test, " + fmt4(check0) + CS + fmt4(check1));
	}

	//	@SuppressWarnings("unused")
	//	public static void test2() {
	//		double hyp = 50;
	//		double deg = 30;
	//		double ang = Math.toRadians(deg);
	//		double y = hyp * Math.sin(ang);
	//		double x = hyp * Math.cos(ang);
	//		double [] da = handleRotation_V1(x, y, ang);
	//		double xp = da[0];
	//		double yp = da[1];
	//	}


	/**
	 * @param args
	 */
	public static void main(String[] args) {
		//DivisionCaller dc = new DivisionCaller("ADL", 11.1);
		//dc.readNewRules();
		//println("main, " + dc.iRulesHash.size());
		//		test2();
	}

	private static void println(String s) {System.out.println(s);}
	private static final String CS = ", ", C = ",", TAB = "\t";
	private static final DecimalFormat DF4 = new DecimalFormat("####.####");
	private static String fmt4(double d) {return DF4.format(d);}


	private static final double[] AP_canonical_orientation = {-1, 0, 0}; // A points down the negative x axis in canonical orienatation
	private static final double[] LR_canonical_orientation = {0, 0, 1}; // L points out toward the viewer in canonical orienatation
	//    private static final double[] DV_canonical_orientation = {0, -1, 0};
	private static final Point3D AP_can_or = new Point3D(AP_canonical_orientation[0], AP_canonical_orientation[1], AP_canonical_orientation[2]);
	private static final Point3D LR_can_or = new Point3D(LR_canonical_orientation[0], LR_canonical_orientation[1], LR_canonical_orientation[2]);
	//    private static final Point3D DV_can_or = new Point3D(DV_canonical_orientation[0], DV_canonical_orientation[1], DV_canonical_orientation[2]);
	private static final int THREE = 3;
	private static final double ZERO_THRESHOLD = .1;    
}